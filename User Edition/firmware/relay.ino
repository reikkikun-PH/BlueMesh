#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEAdvertising.h>
#include <vector>
#include <algorithm>
#include <map>
#include <queue>
#include <atomic>
#include <freertos/semphr.h>

#define MESH_SERVICE_UUID        "12345678-1234-5678-1234-567890abcdef"
#define USER_SERVICE_UUID        "b17c8a70-8bde-4d76-bc3e-1b32d2f7881c"

const size_t CACHE_MAX_SIZE = 200;
const size_t USER_CACHE_MAX_SIZE = 50;
const size_t MAX_QUEUE_SIZE = 16;
const int   SCAN_DURATION_SEC = 5;
const int   ADV_DURATION_MS   = 2500;
const int   QUEUE_HIGH_WATER  = 10;
const float RSSI_HYSTERESIS   = 5.0f;

struct AdvertiseEntry {
    enum Type { MESH_MESSAGE, USER_ADVERTISEMENT };
    Type type;
    std::string data;
};

std::vector<uint32_t> messageCache;
std::vector<uint64_t> userRelayCache;
std::queue<AdvertiseEntry> advertiseQueue;
SemaphoreHandle_t stateMutex = nullptr;
std::atomic<bool> advertisingMode(false);

std::map<uint32_t, int> bestRssiPerMsg;

uint32_t relayInstanceId;
uint32_t relayedByTag;

BLEScan* pBLEScan = nullptr;
BLEAdvertising* pAdvertising = nullptr;

uint32_t totalUsersDetected = 0;
uint32_t totalUsersRelayed = 0;
uint32_t totalMessagesRelayed = 0;
uint32_t totalMessagesSkipped = 0;
uint32_t scanCount = 0;

std::map<uint32_t, uint32_t> userLogTimestamps;
const uint32_t USER_LOG_INTERVAL_MS = 30000;

void startRelayAdvertising(const AdvertiseEntry& entry, int durationMs);
void onScanComplete(BLEScanResults results);

bool isDuplicate(uint32_t messageId) {
    auto it = std::find(messageCache.begin(), messageCache.end(), messageId);
    if (it != messageCache.end()) return true;
    if (messageCache.size() >= CACHE_MAX_SIZE) {
        messageCache.erase(messageCache.begin());
    }
    messageCache.push_back(messageId);
    return false;
}

bool isUserRelayed(uint64_t uuid) {
    auto it = std::find(userRelayCache.begin(), userRelayCache.end(), uuid);
    if (it != userRelayCache.end()) return true;
    if (userRelayCache.size() >= USER_CACHE_MAX_SIZE) {
        userRelayCache.erase(userRelayCache.begin());
    }
    userRelayCache.push_back(uuid);
    return false;
}

class RelayScanCallbacks: public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
        bool isMesh = advertisedDevice.isAdvertisingService(BLEUUID(MESH_SERVICE_UUID));
        bool isUser = advertisedDevice.isAdvertisingService(BLEUUID(USER_SERVICE_UUID));

        if (!isMesh && !isUser) return;
        if (!advertisedDevice.haveManufacturerData()) return;

        auto rawData = advertisedDevice.getManufacturerData();
        std::string mData(rawData.c_str(), rawData.length());

        if (isUser) {
            if (mData.length() < 11) return;

            uint8_t passcodeFlag = (uint8_t)mData[10];

            if (passcodeFlag & 0x10) return;

            uint64_t userUuid = 0;
            memcpy(&userUuid, &mData[2], 8);

            std::string displayName = "";
            if (mData.length() > 11) displayName = mData.substr(11);

            totalUsersDetected++;

            uint32_t userPrefix = (uint32_t)(userUuid >> 32);
            uint32_t now = millis();
            auto lastLog = userLogTimestamps.find(userPrefix);
            bool shouldLog = (lastLog == userLogTimestamps.end() || (now - lastLog->second) > USER_LOG_INTERVAL_MS);
            if (shouldLog) {
                userLogTimestamps[userPrefix] = now;
                Serial.printf("> Detected user: %s\n", displayName.c_str());
            }

            if (stateMutex == nullptr || xSemaphoreTake(stateMutex, portMAX_DELAY) != pdTRUE) return;

            if (!isUserRelayed(userUuid)) {
                if (advertiseQueue.size() < MAX_QUEUE_SIZE) {
                    std::string tagged = mData;
                    tagged[10] = passcodeFlag | 0x10;
                    advertiseQueue.push(AdvertiseEntry{AdvertiseEntry::USER_ADVERTISEMENT, tagged});
                    totalUsersRelayed++;
                    Serial.printf("> Forwarding user: %s (queue: %d/%d)\n",
                        displayName.c_str(), advertiseQueue.size(), MAX_QUEUE_SIZE);
                }
            }
            xSemaphoreGive(stateMutex);
            return;
        }

        if (isMesh && !advertisedDevice.isConnectable()) {
            if (mData.length() < 14) return;

            uint32_t msgId = 0;
            msgId |= (uint8_t)mData[2] << 24;
            msgId |= (uint8_t)mData[3] << 16;
            msgId |= (uint8_t)mData[4] << 8;
            msgId |= (uint8_t)mData[5];

            if ((msgId & 0xFF) == relayedByTag) return;

            int rssi = advertisedDevice.getRSSI();
            auto bestIt = bestRssiPerMsg.find(msgId);
            if (bestIt != bestRssiPerMsg.end() && rssi < bestIt->second - RSSI_HYSTERESIS) {
                totalMessagesSkipped++;
                return;
            }

            if (stateMutex == nullptr || xSemaphoreTake(stateMutex, portMAX_DELAY) != pdTRUE) return;

            if (!isDuplicate(msgId)) {
                if (advertiseQueue.size() < MAX_QUEUE_SIZE) {
                    std::string tagged = mData;
                    if (tagged.size() >= 6) {
                        tagged[5] = relayedByTag;
                    }

                    advertiseQueue.push(AdvertiseEntry{AdvertiseEntry::MESH_MESSAGE, tagged});
                    bestRssiPerMsg[msgId] = rssi;
                    totalMessagesRelayed++;

                    std::string msgText = "";
                    if (mData.length() > 14) msgText = mData.substr(14);

                    if (msgText.length() > 0) {
                        Serial.printf("> Message queued: \"%s\" (queue: %d/%d)\n",
                            msgText.c_str(), advertiseQueue.size(), MAX_QUEUE_SIZE);
                    } else {
                        Serial.printf("> Message queued (queue: %d/%d)\n",
                            advertiseQueue.size(), MAX_QUEUE_SIZE);
                    }
                } else {
                    Serial.println("! Message dropped - forwarding queue is full");
                }
            }
            xSemaphoreGive(stateMutex);
        }
    }
};

void sendRelayBeacon() {
    Serial.println("> Announcing relay presence to phones nearby...");
    uint8_t beaconPayload[10] = {0};
    beaconPayload[8] = 0x10;
    std::string mfgData((const char*)beaconPayload, 9);
    mfgData += "Relay";

    BLEAdvertisementData adData;
    adData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
    adData.setCompleteServices(BLEUUID(USER_SERVICE_UUID));
    pAdvertising->setAdvertisementData(adData);

    BLEAdvertisementData scanData;
    scanData.setManufacturerData(String(mfgData.data(), mfgData.length()));
    pAdvertising->setScanResponseData(scanData);

    pAdvertising->start();
    delay(800);
    pAdvertising->stop();
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("================================================");
    Serial.println("  BlueMesh Relay v5");
    Serial.println("  Range extender for phone-to-phone messaging");
    Serial.println("================================================");

    relayInstanceId = esp_random();
    relayedByTag = relayInstanceId & 0xFF;

    stateMutex = xSemaphoreCreateMutex();

    BLEDevice::init("BlueMesh-Relay");
    BLEDevice::setPower(ESP_PWR_LVL_P9);

    pBLEScan = BLEDevice::getScan();
    pBLEScan->setAdvertisedDeviceCallbacks(new RelayScanCallbacks(), true);
    pBLEScan->setActiveScan(true);
    pBLEScan->setInterval(100);
    pBLEScan->setWindow(99);

    pAdvertising = BLEDevice::getAdvertising();

    Serial.println();
    Serial.println("Ready! Listening for phones...");
    Serial.println("Can queue up to 16 messages at a time");

    sendRelayBeacon();
}

void startRelayAdvertising(const AdvertiseEntry& entry, int durationMs) {
    if (entry.type == AdvertiseEntry::MESH_MESSAGE) {
        uint32_t msgId = 0;
        if (entry.data.length() >= 6) {
            msgId |= (uint8_t)entry.data[2] << 24;
            msgId |= (uint8_t)entry.data[3] << 16;
            msgId |= (uint8_t)entry.data[4] << 8;
            msgId |= (uint8_t)entry.data[5];
        }

        Serial.println("> Broadcasting message to nearby phones...");

        BLEAdvertisementData oAdvertisementData;
        oAdvertisementData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
        oAdvertisementData.setCompleteServices(BLEUUID(MESH_SERVICE_UUID));
        pAdvertising->setAdvertisementData(oAdvertisementData);

        BLEAdvertisementData oScanResponseData;
        oScanResponseData.setManufacturerData(String(entry.data.data(), entry.data.length()));
        pAdvertising->setScanResponseData(oScanResponseData);

    } else {
        std::string displayName = "";
        if (entry.data.length() > 11) displayName = entry.data.substr(11);

        Serial.printf("> Broadcasting user presence: %s\n", displayName.c_str());

        BLEAdvertisementData userAdData;
        userAdData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
        userAdData.setCompleteServices(BLEUUID(USER_SERVICE_UUID));
        pAdvertising->setAdvertisementData(userAdData);

        BLEAdvertisementData userScanData;
        userScanData.setManufacturerData(String(entry.data.data(), entry.data.length()));
        pAdvertising->setScanResponseData(userScanData);
    }

    pAdvertising->start();
    delay(durationMs);
    pAdvertising->stop();
}

void onScanComplete(BLEScanResults results) {
    scanCount++;
    if (scanCount % 5 == 0) {
        Serial.printf("=== Status after scan #%u ===\n", scanCount);
        Serial.printf("  Messages forwarded: %u\n", totalMessagesRelayed);
        Serial.printf("  Users detected: %u\n", totalUsersDetected);
        Serial.printf("  Users forwarded: %u\n", totalUsersRelayed);
        Serial.printf("  Messages skipped (weak signal): %u\n", totalMessagesSkipped);
        Serial.printf("  Devices heard: %d\n", results.getCount());
        Serial.println("===========================");
    } else {
        Serial.printf("> Listening sweep #%u complete - heard %d devices\n", scanCount, results.getCount());
    }
}

void loop() {
    bool hasPayload = false;
    AdvertiseEntry currentEntry;
    size_t currentQueueSize = 0;

    if (stateMutex != nullptr && xSemaphoreTake(stateMutex, portMAX_DELAY) == pdTRUE) {
        currentQueueSize = advertiseQueue.size();
        if (!advertiseQueue.empty()) {
            currentEntry = advertiseQueue.front();
            advertiseQueue.pop();
            hasPayload = true;
        }
        xSemaphoreGive(stateMutex);
    }

    if (hasPayload) {
        if (pBLEScan != nullptr && pBLEScan->isScanning()) {
            pBLEScan->stop();
        }

        advertisingMode = true;
        float multiplier = 1.0f;
        if (currentQueueSize > QUEUE_HIGH_WATER) multiplier = 1.5f;
        int adDuration = (int)(ADV_DURATION_MS * multiplier);

        startRelayAdvertising(currentEntry, adDuration);
        advertisingMode = false;

        delay(10);
        return;
    }

    if (pBLEScan != nullptr && !pBLEScan->isScanning()) {
        if (scanCount > 0 && scanCount % 3 == 0) {
            sendRelayBeacon();
        }

        userRelayCache.clear();

        pBLEScan->start(SCAN_DURATION_SEC, onScanComplete, false);

        if (scanCount == 0) {
            Serial.println("> Listening for phones and messages...");
        }

        if (scanCount % 10 == 0 && bestRssiPerMsg.size() > CACHE_MAX_SIZE) {
            auto it = bestRssiPerMsg.begin();
            std::advance(it, bestRssiPerMsg.size() - CACHE_MAX_SIZE);
            bestRssiPerMsg.erase(bestRssiPerMsg.begin(), it);
        }
    }

    delay(10);
}
