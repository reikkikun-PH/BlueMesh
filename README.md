# BlueMesh

BlueMesh is an offline messaging application for Android. It lets you discover, connect, and chat with nearby devices using Bluetooth Low Energy (BLE)—**completely independent of the internet, cellular networks, or Wi-Fi**. 

Whether you are in a remote area, an outdoor adventure, or in an emergency scenario with no signal, BlueMesh keeps you connected.

---

## Features

### For Everyday Users
* **Internet-Free Messaging**: Send text messages directly to nearby phones. No SIM cards, cellular data, or Wi-Fi required.
* **Passcode Protection**: Secure the app with a 4-digit PIN to prevent unauthorized access to your local chats.
* **Offline Queueing (Send Later)**: If a friend walks away, you can still type and queue your messages. The app automatically sends them the moment they are back in range.
* **Proximity Detection**: See an estimate of how far away your friends are based on Bluetooth signal strength (only shown if they choose to share their location status).
* **Battery Optimization**: Intelligently manages Bluetooth scanning in the background to save your phone's battery.
* **Modern User Interface**: Clean dark theme with seamless transitions and automatic keyboard adjustments.

### For Tech Enthusiasts & Developers
* **Direct P2P GATT Connections**: Establishes point-to-point BLE GATT connections for reliable, high-throughput transmission.
* **Flooding Mesh Relay Support**: Extends communication range using inexpensive ESP32 hardware relays that repeat messages across a local flooding mesh.
* **Secure E2EE (AES-GCM)**: Features optional End-to-End Encryption (E2EE) with Elliptic-Curve Diffie-Hellman (ECDH) key exchange and AES-GCM-128 encryption.
* **Dynamic Packetizer & Compression**: Compresses data with LZ4 and splits payloads dynamically to match negotiated BLE MTU sizes safely.
* **IME Inset Alignment**: Excludes keyboard height via Compose layout structures to ensure perfect input row positioning across all screen sizes.

---

## User Guide

### 1. Getting Started
1. **Set Up Your Profile**: Enter your screen display name (this is what nearby users will see).
2. **Create a Passcode**: Set a 4-digit PIN. This encrypts and secures your chats locally.

### 2. Discovering Peers
1. Make sure your Bluetooth and Location services are enabled (Android requires location permissions to scan for nearby Bluetooth devices).
2. On the home dashboard, tap **Rescan** to search for nearby users.
3. Toggle **Make Discoverable** to allow other phones running BlueMesh to find and connect to you.

### 3. Chatting
1. Tap a peer from the **Nearby Peers** list. The app will establish a secure connection and display **Connected**.
2. If you navigate away or minimize the app, the connection is kept alive in the background so you can still send and receive messages instantly.
3. If the connection is lost (out of range), type your messages normally. They will save as **Sending...** and transmit automatically when the peer is back in range.

---

## Flooding Mesh Relay Protocol (ESP32)

To extend the range of the offline network, BlueMesh supports standalone ESP32 hardware routing nodes using a flooding protocol:

1. **Service UUID**: All nodes filter and broadcast on a fixed UUID: `12345678-1234-5678-1234-567890abcdef`.
2. **Manufacturer Data**: Message packets are broadcasted in the BLE advertisement payload under Manufacturer ID `0xFFFF`.
3. **Packet Layout**:
   - **Bytes 0-3**: A `uint32_t` Message ID (Network/Big Endian) used by relays for message deduplication.
   - **Bytes 4+**: Raw UTF-8 bytes of the chat message.

---

## Developer Guide & Tech Stack

### Technology Stack
* **Language**: Kotlin 1.9+ (Android Client), C++ (ESP32 Firmware)
* **UI Framework**: Jetpack Compose with Material Design 3
* **Architecture**: MVVM (Model-View-ViewModel) + Repository Pattern
* **Database**: SQLite via native helper (stores offline queue, contacts, and session keys)
* **Compression**: LZ4 (Fast compression library)
* **Encryption**: ECDH (Key Agreement) + AES-GCM-128 (authenticated encryption)
* **Target SDK**: Android 16 (API 36)

### Project Structure
```
├── app/
│   ├── src/main/java/com/example/bluemesh/
│   │   ├── MainActivity.kt        # Edge-to-edge drawing setup and runtime permissions
│   │   ├── Navigation.kt          # Compose Navigation architecture
│   │   ├── bluetooth/
│   │   │   └── BluetoothHandler.kt # Advertisements, scanning, and GATT operations
│   │   ├── data/
│   │   │   ├── DefaultDataRepository.kt # Main repo orchestrating database queue & E2EE key sync
│   │   │   └── OfflineQueueDbHelper.kt  # SQLite database helper for queue and contacts
│   │   └── ui/
│   │       ├── chat/              # Chat conversation screen and ViewModel
│   │       ├── lock/              # PIN pad lock screens
│   │       └── main/              # Main dashboard view
│   └── build.gradle.kts           # App gradle build configuration
├── firmware/
│   └── relay.ino                  # ESP32 flooding mesh relay firmware
├── Dummy build/                   # Directory containing latest pre-compiled APKs
└── README.md                      # This guide
```

---

## Building & Running

### Android App
1. Open the project in **Android Studio**.
2. Ensure you have **JDK 17** set as your Gradle JDK.
3. Build the debug version or release version:
   ```powershell
   # Compile Debug APK
   .\gradlew.bat assembleDebug

   # Compile Release APK
   .\gradlew.bat assembleRelease
   ```
4. Output APKs can be found in:
   - Debug: `app/build/outputs/apk/debug/app-debug.apk`
   - Release: `app/build/outputs/apk/release/app-release.apk`

### ESP32 Firmware
1. Open `firmware/relay.ino` in the **Arduino IDE**.
2. Install the ESP32 board support package (version 2.x or higher).
3. Select your ESP32 board model (e.g., *ESP32 Dev Module*).
4. Compile and upload to your device.
