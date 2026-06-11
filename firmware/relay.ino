#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEAdvertising.h>
#include <vector>
#include <algorithm>
#include "esp_gap_ble_api.h"

// Define the Service UUID to filter and transmit on
#define SERVICE_UUID "12345678-1234-5678-1234-567890abcdef"

// Sliding window cache configuration
const size_t CACHE_MAX_SIZE = 50;
std::vector<uint32_t> messageCache;

// State management variables
volatile bool newPayloadAvailable = false;
volatile bool advertisingMode = false;
std::string payloadToAdvertise = "";

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

// BLE Scan Callback
class RelayScanCallbacks: public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
        // If we are currently transitioning or advertising, ignore scan results
        if (advertisingMode || newPayloadAvailable) {
            return;
        }

        // Filter by Service UUID
        if (advertisedDevice.isAdvertisingService(BLEUUID(SERVICE_UUID))) {
            if (advertisedDevice.haveManufacturerData()) {
                std::string mData = advertisedDevice.getManufacturerData();
                
                // Distinguish between connectable (Peer Discovery) and non-connectable (Mesh Message)
                if (advertisedDevice.isConnectable()) {
                    // Peer Discovery (User advertisement)
                    // Format: 2 bytes Company ID + 16 bytes UUID + 1 byte passcode flag + Name (0-10 bytes)
                    if (mData.length() >= 19) {
                        const uint8_t* uuid = (const uint8_t*)&mData[2];
                        uint8_t passcodeFlag = mData[18];
                        std::string displayName = "";
                        if (mData.length() > 19) {
                            displayName = mData.substr(19);
                        }
                        
                        totalUsersDetected++;
                        Serial.println("\n┌────────────────────────────────────────────────────────┐");
                        Serial.printf("│ 👤 [USER DETECTED]                                     │\n");
                        Serial.println("├────────────────────────────────────────────────────────┤");
                        Serial.printf("│ Display Name:  %-40s │\n", displayName.c_str());
                        Serial.printf("│ Passcode PIN:  %-40s │\n", (passcodeFlag == 1) ? "Locked (Required)" : "None (Disposable)");
                        Serial.printf("│ UUID:          %02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x │\n",
                                      uuid[0], uuid[1], uuid[2], uuid[3],
                                      uuid[4], uuid[5],
                                      uuid[6], uuid[7],
                                      uuid[8], uuid[9],
                                      uuid[10], uuid[11], uuid[12], uuid[13], uuid[14], uuid[15]);
                        Serial.printf("│ RSSI Strength: %-5d dBm                                │\n", advertisedDevice.getRSSI());
                        Serial.printf("│ Total Seen:    %-5u                                    │\n", totalUsersDetected);
                        Serial.println("└────────────────────────────────────────────────────────┘\n");
                    }
                } else {
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

                        if (!isDuplicate(msgId)) {
                            totalMessagesRelayed++;
                            Serial.println("\n┌────────────────────────────────────────────────────────┐");
                            Serial.printf("│ 📶 [RELAYING STORE-AND-FORWARD MESSAGE]                │\n");
                            Serial.println("├────────────────────────────────────────────────────────┤");
                            Serial.printf("│ Message ID:    %-40u │\n", msgId);
                            Serial.printf("│ Sender Hash:   %08X                                 │\n", senderHash);
                            Serial.printf("│ Recipient Hash:%08X                                 │\n", recipientHash);
                            Serial.printf("│ Content:       \"%-38s\" │\n", msgText.c_str());
                            Serial.printf("│ RSSI Strength: %-5d dBm                                │\n", advertisedDevice.getRSSI());
                            Serial.printf("│ Relay Action:  Broadcasting for 4s at +9dBm            │\n");
                            Serial.printf("│ Total Relayed: %-5u                                    │\n", totalMessagesRelayed);
                            Serial.println("└────────────────────────────────────────────────────────┘\n");
                            
                            // Capture payload and flag transition
                            payloadToAdvertise = mData;
                            newPayloadAvailable = true;

                            // Stop scanning immediately to free the RF hardware
                            BLEDevice::getScan()->stop();
                        } else {
                            Serial.printf("[Relay] Intercepted message ID=%u (Duplicate, ignored)\n", msgId);
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
    // If no new payload has been detected, run a scan cycle
    if (!newPayloadAvailable) {
        Serial.println("[Relay] Scanning for BlueMesh signals (Users / Messages)...");

        // Scan for 5 seconds. If a new payload is found, onResult() calls stop() 
        // which makes start() return immediately.
        pBLEScan->start(5, false);
        
        // Clear scan results to release memory and prevent heap fragmentation
        pBLEScan->clearResults();
    }

    // Process state transition if a new packet was captured
    if (newPayloadAvailable) {
        newPayloadAvailable = false;
        advertisingMode = true;

        Serial.println("[Relay] Switching to Advertising Mode...");

        // Extract Message ID from payloadToAdvertise
        uint32_t msgId = 0;
        if (payloadToAdvertise.length() >= 6) {
            msgId |= (uint8_t)payloadToAdvertise[2] << 24;
            msgId |= (uint8_t)payloadToAdvertise[3] << 16;
            msgId |= (uint8_t)payloadToAdvertise[4] << 8;
            msgId |= (uint8_t)payloadToAdvertise[5];
        }
        Serial.printf("[Relay] Broadcasting relayed message ID=%u at +9dBm for 4 seconds...\n", msgId);

        // Construct Advertisement Data
        BLEAdvertisementData oAdvertisementData;
        // Flags: General Discoverable, BR/EDR Not Supported
        oAdvertisementData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
        // Add Service UUID
        oAdvertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
        // Add exact Manufacturer Data payload (Company ID + Message ID + Message Text)
        oAdvertisementData.setManufacturerData(String(payloadToAdvertise.data(), payloadToAdvertise.length()));

        pAdvertising->setAdvertisementData(oAdvertisementData);

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
