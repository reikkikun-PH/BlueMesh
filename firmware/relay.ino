#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEAdvertising.h>
#include <vector>
#include <algorithm>
#include "esp_gap_ble_api.h"

// Define the Service UUIDs for mesh communications and user discovery
#define MESH_SERVICE_UUID "12345678-1234-5678-1234-567890abcdef"
#define USER_SERVICE_UUID "b17c8a70-8bde-4d76-bc3e-1b32d2f7881c"

#include <queue>
#include <atomic>
#include <freertos/semphr.h>

// Sliding window cache configuration
const size_t CACHE_MAX_SIZE = 50;
std::vector<uint32_t> messageCache;

// State management variables
std::queue<std::string> advertiseQueue;
const size_t MAX_QUEUE_SIZE = 4;
SemaphoreHandle_t stateMutex = nullptr;
std::atomic<bool> advertisingMode(false);

// Global BLE objects
BLEScan* pBLEScan = nullptr;
BLEAdvertising* pAdvertising = nullptr;

// Deduplication function
bool isDuplicate(uint32_t messageId) {
    auto it = std::find(messageCache.begin(), messageCache.end(), messageId);
    if (it != messageCache.end()) {
        return true;
    }
    if (messageCache.size() >= CACHE_MAX_SIZE) {
        messageCache.erase(messageCache.begin()); // Remove oldest
    }
    messageCache.push_back(messageId);
    return false;
}

// Statistics counters
uint32_t totalUsersDetected = 0;
uint32_t totalMessagesRelayed = 0;

class RelayScanCallbacks: public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
        bool isMesh = advertisedDevice.isAdvertisingService(BLEUUID(MESH_SERVICE_UUID));
        bool isUser = advertisedDevice.isAdvertisingService(BLEUUID(USER_SERVICE_UUID));
        
        // Filter by Service UUID
        if (isMesh || isUser) {
            if (advertisedDevice.haveManufacturerData()) {
                auto rawData = advertisedDevice.getManufacturerData();
                std::string mData(rawData.c_str(), rawData.length());
                
                // Distinguish between connectable (Peer Discovery) and non-connectable (Mesh Message)
                if (isUser && advertisedDevice.isConnectable()) {
                    // Peer Discovery (User advertisement)
                    // Format: 2 bytes Company ID + 8 bytes short UUID + 1 byte passcode flag + Name (0-N bytes)
                    if (mData.length() >= 11) {
                        const uint8_t* uuid = (const uint8_t*)&mData[2];
                        uint8_t passcodeFlag = mData[10];
                        std::string displayName = "";
                        if (mData.length() > 11) {
                            displayName = mData.substr(11);
                        }
                        
                        totalUsersDetected++;
                        Serial.println("\n┌────────────────────────────────────────────────────────┐");
                        Serial.printf("│ 👤 [USER DETECTED]                                     │\n");
                        Serial.println("├────────────────────────────────────────────────────────┤");
                        Serial.printf("│ Display Name:  %-40s │\n", displayName.c_str());
                        Serial.printf("│ Passcode PIN:  %-40s │\n", (passcodeFlag == 1) ? "Locked (Required)" : "None (Disposable)");
                        Serial.printf("│ Short UUID:    %02x%02x%02x%02x%02x%02x%02x%02x                        │\n",
                                      uuid[0], uuid[1], uuid[2], uuid[3],
                                      uuid[4], uuid[5], uuid[6], uuid[7]);
                        Serial.printf("│ RSSI Strength: %-5d dBm                                │\n", advertisedDevice.getRSSI());
                        Serial.printf("│ Total Seen:    %-5u                                    │\n", totalUsersDetected);
                        Serial.println("└────────────────────────────────────────────────────────┘\n");
                    }
                } else if (isMesh && !advertisedDevice.isConnectable()) {
                    // Mesh Message
                    // Format: 2 bytes Company ID + 4 bytes msgId + 4 bytes senderHash + 4 bytes recipientHash + Message Text
                    if (mData.length() >= 14) {
                        // Extract Message ID (Big Endian)
                        uint32_t msgId = 0;
                        msgId |= (uint8_t)mData[2] << 24;
                        msgId |= (uint8_t)mData[3] << 16;
                        msgId |= (uint8_t)mData[4] << 8;
                        msgId |= (uint8_t)mData[5];
 
                        // Extract Sender Hash (Big Endian)
                        uint32_t senderHash = 0;
                        senderHash |= (uint8_t)mData[6] << 24;
                        senderHash |= (uint8_t)mData[7] << 16;
                        senderHash |= (uint8_t)mData[8] << 8;
                        senderHash |= (uint8_t)mData[9];
 
                        // Extract Recipient Hash (Big Endian)
                        uint32_t recipientHash = 0;
                        recipientHash |= (uint8_t)mData[10] << 24;
                        recipientHash |= (uint8_t)mData[11] << 16;
                        recipientHash |= (uint8_t)mData[12] << 8;
                        recipientHash |= (uint8_t)mData[13];
 
                        std::string msgText = "";
                        if (mData.length() > 14) {
                            msgText = mData.substr(14);
                        }
 
                        if (stateMutex != nullptr && xSemaphoreTake(stateMutex, portMAX_DELAY) == pdTRUE) {
                            if (!isDuplicate(msgId)) {
                                if (advertiseQueue.size() < MAX_QUEUE_SIZE) {
                                    advertiseQueue.push(mData);
                                    totalMessagesRelayed++;
                                    Serial.println("\n┌────────────────────────────────────────────────────────┐");
                                    Serial.printf("│ 📶 [RELAYING STORE-AND-FORWARD MESSAGE QUEUED]         │\n");
                                    Serial.println("├────────────────────────────────────────────────────────┤");
                                    Serial.printf("│ Message ID:    %-40u │\n", msgId);
                                    Serial.printf("│ Sender Hash:   %08X                                 │\n", senderHash);
                                    Serial.printf("│ Recipient Hash:%08X                                 │\n", recipientHash);
                                    Serial.printf("│ Content:       \"%-38s\" │\n", msgText.c_str());
                                    Serial.printf("│ RSSI Strength: %-5d dBm                                │\n", advertisedDevice.getRSSI());
                                    Serial.printf("│ Queue Size:    %d/%d                                   │\n", advertiseQueue.size(), MAX_QUEUE_SIZE);
                                    Serial.printf("│ Total Relayed: %-5u                                    │\n", totalMessagesRelayed);
                                    Serial.println("└────────────────────────────────────────────────────────┘\n");
                                    
                                    // Stop scanning immediately to free the RF hardware if we aren't advertising
                                    if (!advertisingMode) {
                                        BLEDevice::getScan()->stop();
                                    }
                                } else {
                                    Serial.printf("[Relay] Queue full, dropped message ID=%u\n", msgId);
                                }
                            } else {
                                Serial.printf("[Relay] Intercepted message ID=%u (Duplicate, ignored)\n", msgId);
                            }
                            xSemaphoreGive(stateMutex);
                        }
                    }
                }
            }
        }
    }
};

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("[Relay] Initializing BlueMesh ESP32 Infrastructure Relay...");

    // Initialize mutex
    stateMutex = xSemaphoreCreateMutex();

    // Initialize BLE stack
    BLEDevice::init("BlueMesh-Relay");

    // Configure maximum transmit power (+9dBm) for all BLE operations
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV,     ESP_PWR_LVL_P9);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_SCAN,    ESP_PWR_LVL_P9);

    // Initialize Scan
    pBLEScan = BLEDevice::getScan();
    pBLEScan->setAdvertisedDeviceCallbacks(new RelayScanCallbacks(), true); // wantDuplicates = true
    pBLEScan->setActiveScan(true); // Active scan requests scan response (where manufacturer data resides)
    pBLEScan->setInterval(100);
    pBLEScan->setWindow(99);

    // Initialize Advertising
    pAdvertising = BLEDevice::getAdvertising();

    Serial.println("[Relay] BLE Stack Initialized. Starting continuous scan...");
}

void loop() {
    bool hasPayload = false;
    std::string currentPayload = "";

    // Safely check if we have a payload to advertise
    if (stateMutex != nullptr && xSemaphoreTake(stateMutex, portMAX_DELAY) == pdTRUE) {
        if (!advertiseQueue.empty()) {
            currentPayload = advertiseQueue.front();
            advertiseQueue.pop();
            hasPayload = true;
        }
        xSemaphoreGive(stateMutex);
    }

    // If no new payload has been detected, run a scan cycle
    if (!hasPayload) {
        Serial.println("[Relay] Scanning for BlueMesh signals (Users / Messages)...");

        // Scan for 5 seconds. If a new payload is found, onResult() calls stop() 
        // which makes start() return immediately.
        pBLEScan->start(5, false);
        
        // Clear scan results to release memory and prevent heap fragmentation
        pBLEScan->clearResults();
    } else {
        // We have a payload, switch to advertising mode
        advertisingMode = true;
        Serial.println("[Relay] Switching to Advertising Mode...");

        // Extract Message ID from currentPayload
        uint32_t msgId = 0;
        if (currentPayload.length() >= 6) {
            msgId |= (uint8_t)currentPayload[2] << 24;
            msgId |= (uint8_t)currentPayload[3] << 16;
            msgId |= (uint8_t)currentPayload[4] << 8;
            msgId |= (uint8_t)currentPayload[5];
        }
        Serial.printf("[Relay] Broadcasting relayed message ID=%u at +9dBm for 4 seconds...\n", msgId);

        // Construct Advertisement Data
        BLEAdvertisementData oAdvertisementData;
        // Flags: General Discoverable, BR/EDR Not Supported
        oAdvertisementData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
        // Add Service UUID
        oAdvertisementData.setCompleteServices(BLEUUID(MESH_SERVICE_UUID));
        pAdvertising->setAdvertisementData(oAdvertisementData);

        // Construct Scan Response Data (prevents exceeding the 31-byte BLE limit)
        BLEAdvertisementData oScanResponseData;
        // Add exact Manufacturer Data payload (Company ID + Message ID + Message Text)
        oScanResponseData.setManufacturerData(String(currentPayload.data(), currentPayload.length()));
        pAdvertising->setScanResponseData(oScanResponseData);

        // Start broadcasting
        pAdvertising->start();
        
        // Ensure transmit power is set to maximum (+9dBm)
        esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);

        Serial.println("[Relay] Broadcasting payload at +9dBm for 4 seconds...");
        delay(4000); // 4-second blast window

        // Stop advertising
        pAdvertising->stop();
        Serial.println("[Relay] Broadcast finished. Re-entering Scanning Mode...");

        // Reset state variables
        advertisingMode = false;
    }

    // Yield to avoid watchdog timeout issues
    delay(10);
}
