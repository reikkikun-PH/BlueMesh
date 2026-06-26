#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEAdvertising.h>
#include <map>

#define MESH_SERVICE_UUID        "12345678-1234-5678-1234-567890abcdef"
#define USER_SERVICE_UUID        "b17c8a70-8bde-4d76-bc3e-1b32d2f7881c"

const int   SCAN_DURATION_SEC = 5;

uint32_t relayInstanceId;

BLEScan* pBLEScan = nullptr;
BLEAdvertising* pAdvertising = nullptr;

uint32_t totalUsersDetected = 0;
uint32_t scanCount = 0;

std::map<uint32_t, uint32_t> userLogTimestamps;
const uint32_t USER_LOG_INTERVAL_MS = 30000;

void onScanComplete(BLEScanResults results);

class RelayScanCallbacks: public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
        bool isUser = advertisedDevice.isAdvertisingService(BLEUUID(USER_SERVICE_UUID));

        if (!isUser) return;
        if (!advertisedDevice.haveManufacturerData()) return;

        auto rawData = advertisedDevice.getManufacturerData();
        std::string mData(rawData.c_str(), rawData.length());

        if (mData.length() < 11) return;

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
    Serial.println("  BlueMesh Relay v5 (Passive Mode)");
    Serial.println("  Monitoring presence only — no data forwarding");
    Serial.println("================================================");

    relayInstanceId = esp_random();

    BLEDevice::init("BlueMesh-Relay");
    BLEDevice::setPower(ESP_PWR_LVL_P9);

    pBLEScan = BLEDevice::getScan();
    pBLEScan->setAdvertisedDeviceCallbacks(new RelayScanCallbacks(), true);
    pBLEScan->setActiveScan(true);
    pBLEScan->setInterval(100);
    pBLEScan->setWindow(99);

    pAdvertising = BLEDevice::getAdvertising();

    Serial.println();
    Serial.println("Ready! Listening for phones... (passive mode)");

    sendRelayBeacon();
}

void onScanComplete(BLEScanResults results) {
    scanCount++;
    if (scanCount % 5 == 0) {
        Serial.printf("=== Status after sweep #%u ===\n", scanCount);
        Serial.printf("  Users detected: %u\n", totalUsersDetected);
        Serial.printf("  Devices heard: %d\n", results.getCount());
        Serial.println("===========================");
    } else {
        Serial.printf("> Listening sweep #%u complete - heard %d devices\n", scanCount, results.getCount());
    }
}

void loop() {
    if (pBLEScan != nullptr && !pBLEScan->isScanning()) {
        if (scanCount > 0 && scanCount % 3 == 0) {
            sendRelayBeacon();
        }

        pBLEScan->start(SCAN_DURATION_SEC, onScanComplete, false);

        if (scanCount == 0) {
            Serial.println("> Listening for phones...");
        }
    }

    delay(10);
}
