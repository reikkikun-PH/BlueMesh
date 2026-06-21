#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEAdvertising.h>
#include <vector>
#include <algorithm>
#include <map>
#include "esp_gap_ble_api.h"

#define MESH_SERVICE_UUID "12345678-1234-5678-1234-567890abcdef"
#define USER_SERVICE_UUID "b17c8a70-8bde-4d76-bc3e-1b32d2f7881c"

#include <queue>
#include <atomic>
#include <freertos/semphr.h>

// ----------------------------------------------------------------
// Configuration
// ----------------------------------------------------------------
const size_t CACHE_MAX_SIZE = 200;                     // Dedup cache: remember this many message IDs
const size_t MAX_QUEUE_SIZE = 16;                      // Larger queue for burst handling
const int   SCAN_DURATION_SEC = 5;                     // Full scan cycle duration
const int   ADV_DURATION_MS   = 2500;                  // Shorter ad window (2.5s vs 4s) to return to scan sooner
const int   MIN_SCAN_MS       = 1000;                  // Never stop scanning before this (collect multiple msgs)
const int   QUEUE_HIGH_WATER  = 10;                    // If queue > this, increase ad time
const float RSSI_HYSTERESIS   = 5.0f;                  // dBm threshold to re-relay a message (skip if too weak)

// ----------------------------------------------------------------
// State
// ----------------------------------------------------------------
std::vector<uint32_t> messageCache;
std::queue<std::string> advertiseQueue;
SemaphoreHandle_t stateMutex = nullptr;
std::atomic<bool> advertisingMode(false);

// Per-message RSSI tracking — only re-relay a message if we hear it stronger than before
std::map<uint32_t, int> bestRssiPerMsg;

// Self-broadcast detection: tag each relayed broadcast with a random ID so we can ignore our own
uint32_t relayInstanceId;
uint32_t relayedByTag;  // XOR mask applied to messageId to mark "relayed by this node"

BLEScan* pBLEScan = nullptr;
BLEAdvertising* pAdvertising = nullptr;

uint32_t totalUsersDetected = 0;
uint32_t totalMessagesRelayed = 0;
uint32_t totalMessagesSkipped = 0;
uint32_t scanCount = 0;

// ----------------------------------------------------------------
// Deduplication
// ----------------------------------------------------------------
bool isDuplicate(uint32_t messageId) {
    auto it = std::find(messageCache.begin(), messageCache.end(), messageId);
    if (it != messageCache.end()) return true;
    if (messageCache.size() >= CACHE_MAX_SIZE) {
        messageCache.erase(messageCache.begin());
    }
    messageCache.push_back(messageId);
    return false;
}

// ----------------------------------------------------------------
// Scan callbacks
// ----------------------------------------------------------------
class RelayScanCallbacks: public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
        bool isMesh = advertisedDevice.isAdvertisingService(BLEUUID(MESH_SERVICE_UUID));
        bool isUser = advertisedDevice.isAdvertisingService(BLEUUID(USER_SERVICE_UUID));

        if (!isMesh && !isUser) return;
        if (!advertisedDevice.haveManufacturerData()) return;

        auto rawData = advertisedDevice.getManufacturerData();
        std::string mData(rawData.c_str(), rawData.length());

        if (isUser && advertisedDevice.isConnectable()) {
            // Peer Discovery — just log, don't relay
            if (mData.length() >= 11) {
                const uint8_t* uuid = (const uint8_t*)&mData[2];
                uint8_t passcodeFlag = mData[10];
                std::string displayName = "";
                if (mData.length() > 11) displayName = mData.substr(11);

                totalUsersDetected++;
                Serial.printf("[Relay] 👤 User \"%s\" (UUID: %02x%02x%02x%02x) RSSI=%d\n",
                    displayName.c_str(), uuid[0], uuid[1], uuid[2], uuid[3],
                    advertisedDevice.getRSSI());
            }
            return;
        }

        if (isMesh && !advertisedDevice.isConnectable()) {
            // Mesh Message
            if (mData.length() < 14) return;

            uint32_t msgId = 0;
            msgId |= (uint8_t)mData[2] << 24;
            msgId |= (uint8_t)mData[3] << 16;
            msgId |= (uint8_t)mData[4] << 8;
            msgId |= (uint8_t)mData[5];

            // Skip own broadcasts — ignore messages tagged with our relay instance
            if ((msgId & 0xFF) == relayedByTag) {
                return;
            }

            // RSSI gating: only queue if signal is stronger than our best for this message
            int rssi = advertisedDevice.getRSSI();
            auto bestIt = bestRssiPerMsg.find(msgId);
            if (bestIt != bestRssiPerMsg.end() && rssi < bestIt->second - RSSI_HYSTERESIS) {
                totalMessagesSkipped++;
                return; // Already heard this stronger elsewhere
            }

            if (stateMutex == nullptr || xSemaphoreTake(stateMutex, portMAX_DELAY) != pdTRUE) return;

            if (!isDuplicate(msgId)) {
                if (advertiseQueue.size() < MAX_QUEUE_SIZE) {
                    // Tag the message to mark it as relayed by us on next broadcast
                    std::string tagged = mData;
                    if (tagged.size() >= 6) {
                        tagged[5] = relayedByTag;  // Overwrite low byte of msgId with relay tag
                    }

                    advertiseQueue.push(tagged);
                    bestRssiPerMsg[msgId] = rssi;
                    totalMessagesRelayed++;

                    uint32_t senderHash = 0;
                    senderHash |= (uint8_t)mData[6] << 24;
                    senderHash |= (uint8_t)mData[7] << 16;
                    senderHash |= (uint8_t)mData[8] << 8;
                    senderHash |= (uint8_t)mData[9];

                    uint32_t recipientHash = 0;
                    recipientHash |= (uint8_t)mData[10] << 24;
                    recipientHash |= (uint8_t)mData[11] << 16;
                    recipientHash |= (uint8_t)mData[12] << 8;
                    recipientHash |= (uint8_t)mData[13];

                    std::string msgText = "";
                    if (mData.length() > 14) msgText = mData.substr(14);

                    Serial.printf("[Relay] 📶 QUEUED msgId=%u sender=%08X → %08X \"%s\" q=%d/%d rssi=%d\n",
                        msgId, senderHash, recipientHash, msgText.c_str(),
                        advertiseQueue.size(), MAX_QUEUE_SIZE, rssi);
                } else {
                    Serial.printf("[Relay] ⚠ DROPPED msgId=%u (queue full)\n", msgId);
                }
            }
            xSemaphoreGive(stateMutex);
        }
    }
};

// ----------------------------------------------------------------
// Setup
// ----------------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("[Relay] BlueMesh ESP32 Relay v2 (Multi-User)");

    // Random instance identity — changes on every power cycle
    relayInstanceId = esp_random();
    relayedByTag = relayInstanceId & 0xFF;

    stateMutex = xSemaphoreCreateMutex();

    BLEDevice::init("BlueMesh-Relay");

    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV,     ESP_PWR_LVL_P9);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_SCAN,    ESP_PWR_LVL_P9);

    pBLEScan = BLEDevice::getScan();
    pBLEScan->setAdvertisedDeviceCallbacks(new RelayScanCallbacks(), true);
    pBLEScan->setActiveScan(true);
    pBLEScan->setInterval(100);
    pBLEScan->setWindow(99);

    pAdvertising = BLEDevice::getAdvertising();

    Serial.printf("[Relay] Instance tag=0x%02X  Queue=%d  Cache=%d\n",
        relayedByTag, MAX_QUEUE_SIZE, CACHE_MAX_SIZE);
    Serial.println("[Relay] Ready. Scanning...");
}

// ----------------------------------------------------------------
// Loop
// ----------------------------------------------------------------
void loop() {
    // Check queue (non-blocking mutex)
    bool hasPayload = false;
    std::string currentPayload = "";
    size_t currentQueueSize = 0;

    if (stateMutex != nullptr && xSemaphoreTake(stateMutex, portMAX_DELAY) == pdTRUE) {
        currentQueueSize = advertiseQueue.size();
        if (!advertiseQueue.empty()) {
            currentPayload = advertiseQueue.front();
            advertiseQueue.pop();
            hasPayload = true;
        }
        xSemaphoreGive(stateMutex);
    }

    // --- SCAN MODE ---
    if (!hasPayload) {
        scanCount++;
        Serial.printf("[Relay] Scan #%u (queue=%d, relayed=%u, skipped=%u)...\n",
            scanCount, currentQueueSize, totalMessagesRelayed, totalMessagesSkipped);

        // Full scan — do NOT stop early, collect ALL messages in this cycle
        pBLEScan->start(SCAN_DURATION_SEC, false);

        pBLEScan->clearResults();

        // Periodically trim RSSI cache to prevent memory bloat
        if (scanCount % 10 == 0 && bestRssiPerMsg.size() > CACHE_MAX_SIZE) {
            auto it = bestRssiPerMsg.begin();
            std::advance(it, bestRssiPerMsg.size() - CACHE_MAX_SIZE);
            bestRssiPerMsg.erase(bestRssiPerMsg.begin(), it);
        }

        Serial.printf("[Relay] Scan done. Queue now has %d messages.\n", currentQueueSize);

    // --- ADVERTISING MODE ---
    } else {
        advertisingMode = true;

        uint32_t msgId = 0;
        if (currentPayload.length() >= 6) {
            msgId |= (uint8_t)currentPayload[2] << 24;
            msgId |= (uint8_t)currentPayload[3] << 16;
            msgId |= (uint8_t)currentPayload[4] << 8;
            msgId |= (uint8_t)currentPayload[5];
        }

        // Adaptive ad duration: longer if queue is backed up
        float multiplier = 1.0f;
        if (currentQueueSize > QUEUE_HIGH_WATER) multiplier = 1.5f;

        int adDuration = (int)(ADV_DURATION_MS * multiplier);
        Serial.printf("[Relay] 📡 Broadcasting msgId=%u for %dms (queue=%d)\n",
            msgId, adDuration, currentQueueSize);

        BLEAdvertisementData oAdvertisementData;
        oAdvertisementData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
        oAdvertisementData.setCompleteServices(BLEUUID(MESH_SERVICE_UUID));
        pAdvertising->setAdvertisementData(oAdvertisementData);

        BLEAdvertisementData oScanResponseData;
        // Re-broadcast the EXACT payload (already tagged with our relayedByTag)
        oScanResponseData.setManufacturerData(String(currentPayload.data(), currentPayload.length()));
        pAdvertising->setScanResponseData(oScanResponseData);

        pAdvertising->start();
        esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);

        delay(adDuration);

        pAdvertising->stop();
        advertisingMode = false;
    }

    // Short yield between cycles
    delay(10);
}
