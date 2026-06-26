#include <Arduino.h>
#include <NimBLEDevice.h>
#include <map>
#include <vector>
#include <queue>
#include <algorithm>

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
    // Helper: forward a user advertisement with 0x10 relay flag
    static void processUserAd(const std::string& addr, const std::string& mData, int rssi, uint32_t now) {
        if (mData.length() < 11) return;

        uint8_t passcodeFlag = (uint8_t)mData[10];
        if (passcodeFlag & 0x10) return;  // already relayed

        uint64_t userUuid = 0;
        memcpy(&userUuid, &mData[2], 8);

        std::string displayName = "";
        if (mData.length() > 11) displayName = mData.substr(11);

        totalUsersDetected++;

        uint32_t userPrefix = (uint32_t)(userUuid >> 32);
        auto lastLog = userLogTimestamps.find(userPrefix);
        bool shouldLog = (lastLog == userLogTimestamps.end() || (now - lastLog->second) > USER_LOG_INTERVAL_MS);
        if (shouldLog) {
            userLogTimestamps[userPrefix] = now;
            Serial.printf("> [%s] USER \"%s\" RSSI=%d\n", addr.c_str(), displayName.c_str(), rssi);
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
    }

    // Helper: forward a mesh message with relay tag
    static void processMeshMsg(const std::string& addr, const std::string& mData, int rssi) {
        if (mData.length() < 14) return;

        uint32_t msgId = 0;
        msgId |= (uint8_t)mData[2] << 24;
        msgId |= (uint8_t)mData[3] << 16;
        msgId |= (uint8_t)mData[4] << 8;
        msgId |= (uint8_t)mData[5];

        if ((msgId & 0xFF) == relayedByTag) return;

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
                    Serial.printf("> [%s] MESSAGE queued: \"%s\" (queue: %d/%d)\n",
                        addr.c_str(), msgText.c_str(), advertiseQueue.size(), MAX_QUEUE_SIZE);
                } else {
                    Serial.printf("> [%s] MESSAGE queued (queue: %d/%d)\n",
                        addr.c_str(), advertiseQueue.size(), MAX_QUEUE_SIZE);
                }
            } else {
                Serial.println("! Message dropped - forwarding queue is full");
            }
        }
        xSemaphoreGive(stateMutex);
    }

public:
    // onResult handles only mesh messages (non-connectable ads are reported immediately)
    void onResult(BLEAdvertisedDevice* advertisedDevice) {
        if (!advertisedDevice->isAdvertisingService(BLEUUID(MESH_SERVICE_UUID))) return;
        if (advertisedDevice->isConnectable()) return;
        if (!advertisedDevice->haveManufacturerData()) return;

        auto rawData = advertisedDevice->getManufacturerData();
        std::string mData(rawData.c_str(), rawData.length());

        std::string addr = advertisedDevice->getAddress().toString();
        int rssi = advertisedDevice->getRSSI();

        processMeshMsg(addr, mData, rssi);
    }

    // onScanEnd processes ALL devices with merged data (advertisement + scan response)
    void onScanEnd(const BLEScanResults& results, int reason) {
        scanCount++;
        uint32_t now = millis();
        int count = results.getCount();

        Serial.printf("> Sweep #%u complete — %d devices heard\n", scanCount, count);

        // Every 5 sweeps, show status summary instead of per-device logs
        if (scanCount % 5 == 0) {
            Serial.printf("  Users detected: %u  forwarded: %u\n", totalUsersDetected, totalUsersRelayed);
            Serial.printf("  Messages relayed: %u  skipped: %u\n", totalMessagesRelayed, totalMessagesSkipped);
        }

        for (int i = 0; i < count; i++) {
            const NimBLEAdvertisedDevice* device = results.getDevice(i);
            if (!device || !device->haveManufacturerData()) continue;

            bool isUser = device->isAdvertisingService(BLEUUID(USER_SERVICE_UUID));
            bool isMesh = device->isAdvertisingService(BLEUUID(MESH_SERVICE_UUID));
            if (!isUser && !isMesh) continue;

            std::string mData = device->getManufacturerData();
            std::string addr = device->getAddress().toString();
            int rssi = device->getRSSI();

            if (isUser) {
                processUserAd(addr, mData, rssi, now);
            }

            if (isMesh) {
                processMeshMsg(addr, mData, rssi);
            }
        }
    }
};

void sendRelayBeacon() {
    Serial.println("> Announcing relay presence to phones nearby...");
    // No company ID — this beacon is NOT meant to be parsed as a user ad.
    // Phones detect relay presence via forwarded user ads (with 0x10 relay flag set).
    uint8_t beaconPayload[10] = {0};
    beaconPayload[8] = 0x10;
    std::string mfgData((const char*)beaconPayload, 9);
    mfgData += "Relay";

    BLEAdvertisementData adData;
    adData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
    adData.setCompleteServices(BLEUUID(USER_SERVICE_UUID));
    pAdvertising->setAdvertisementData(adData);

    BLEAdvertisementData scanData;
    scanData.setManufacturerData(mfgData);
    pAdvertising->setScanResponseData(scanData);

    pAdvertising->start();
    delay(800);
    pAdvertising->stop();
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("================================================");
    Serial.println("  BlueMesh Relay v5 (NimBLE)");
    Serial.println("  Range extender for phone-to-phone messaging");
    Serial.println("================================================");

    relayInstanceId = esp_random();
    relayedByTag = relayInstanceId & 0xFF;

    stateMutex = xSemaphoreCreateMutex();

    BLEDevice::init("BlueMesh-Relay");
    BLEDevice::setPower(9);

    pBLEScan = BLEDevice::getScan();
    pBLEScan->setScanCallbacks(new RelayScanCallbacks(), false);
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
        Serial.println("> Broadcasting message to nearby phones...");

        BLEAdvertisementData oAdvertisementData;
        oAdvertisementData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
        oAdvertisementData.setCompleteServices(BLEUUID(MESH_SERVICE_UUID));
        pAdvertising->setAdvertisementData(oAdvertisementData);

        BLEAdvertisementData oScanResponseData;
        oScanResponseData.setManufacturerData(entry.data);
        pAdvertising->setScanResponseData(oScanResponseData);

    } else {
        std::string displayName = "";
        if (entry.data.length() > 11) displayName = entry.data.substr(11);

        Serial.printf("> Broadcasting user presence: %s\n", displayName.c_str());

        BLEAdvertisementData userAdData;
        userAdData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
        userAdData.setCompleteServices(BLEUUID(USER_SERVICE_UUID));
        pAdvertising->setAdvertisementData(userAdData);

        BLEAdvertisementData userScanData;
        userScanData.setManufacturerData(entry.data);
        pAdvertising->setScanResponseData(userScanData);
    }

    pAdvertising->start();
    delay(durationMs);
    pAdvertising->stop();
    delay(20);  // Allow advertising stop to complete before reconfiguration
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

        float multiplier = 1.0f;
        if (currentQueueSize > QUEUE_HIGH_WATER) multiplier = 1.5f;
        int adDuration = (int)(ADV_DURATION_MS * multiplier);

        startRelayAdvertising(currentEntry, adDuration);

        delay(10);
        return;
    }

    if (pBLEScan != nullptr && !pBLEScan->isScanning()) {
        if (scanCount > 0 && scanCount % 3 == 0) {
            sendRelayBeacon();
        }

        userRelayCache.clear();

        pBLEScan->start(SCAN_DURATION_SEC * 1000);

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
