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
                String mDataString = advertisedDevice.getManufacturerData();
                std::string mData(mDataString.c_str(), mDataString.length());
                
                // Expected format: 2 bytes Company ID + 4 bytes Message ID + message body
                // Minimum size is 6 bytes (2 + 4)
                if (mData.length() >= 6) {
                    // Extract Message ID (Big Endian)
                    uint32_t msgId = 0;
                    msgId |= (uint8_t)mData[2] << 24;
                    msgId |= (uint8_t)mData[3] << 16;
                    msgId |= (uint8_t)mData[4] << 8;
                    msgId |= (uint8_t)mData[5];

                    if (!isDuplicate(msgId)) {
                        Serial.printf("[Relay] New unique message captured! ID: %u, Length: %d bytes\n", msgId, mData.length());
                        
                        // Extract text for diagnostic logging
                        std::string msgText = mData.substr(6);
                        Serial.printf("[Relay] Content: %s\n", msgText.c_str());

                        // Capture payload and flag transition
                        payloadToAdvertise = mData;
                        newPayloadAvailable = true;

                        // Stop scanning immediately to free the RF hardware
                        BLEDevice::getScan()->stop();
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
