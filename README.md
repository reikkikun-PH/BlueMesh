# BlueMesh

A peer-to-peer, decentralized Bluetooth mesh messaging application for Android. Connect, chat, and communicate securely without any internet dependency.

---

## Features

- **P2P Mesh Network**: Connect directly to nearby peers to form a local communications mesh.
- **ESP32 Flooding Relay Support**: Seamless range extension using standalone ESP32 nodes that capture, deduplicate, and re-broadcast BLE advertisement messages.
- **Passcode Lock Screen**: Secure numerical PIN lock (SHA-256 protected) enforcing private local access.
- **5-Minute Auto-Lock**: Automatically locks the application if it is backgrounded for more than 5 minutes.
- **Persistent Offline Queue**: Local SQLite database storing queued messages when peers are out of range; messages are automatically synchronized once they reappear.
- **LZ4 Compression**: Payloads are compressed with high-performance LZ4 compression to optimize Bluetooth bandwidth.
- **Fragmented MTU Packetizer**: Messages are dynamically packetized based on negotiated BLE MTU size, written sequentially with a 50ms inter-packet delay.
- **Android 15/16 Immersive Edge-to-Edge**: Full support for native edge-to-edge drawing, rendering backgrounds seamlessly behind transparent status and navigation bars.
- **IME Keyboard Inset Compatibility**: Dual-mode input alignment using Compose insets. Excludes keyboard height from root containers via `WindowInsets.safeDrawing.exclude(WindowInsets.ime)` and applies `.imePadding()` directly to inner content columns. This ensures text areas sit perfectly right on top of the keyboard across both AOSP emulators and physically-resizing OEM ROMs.

---

## Flooding Mesh Relay Protocol

To communicate with physical routing nodes (such as the ESP32), BlueMesh uses a non-connectable flooding protocol over BLE:

1. **Service UUID**: All nodes filter and transmit on a fixed Service UUID: `12345678-1234-5678-1234-567890abcdef`.
2. **Manufacturer Specific Data**: Packets are written into the Scan Response payload under Manufacturer ID `0xFFFF`.
3. **Data Layout**:
   - **Bytes 0-3**: A `uint32_t` Message ID (Network/Big Endian) used by the relay for sliding window deduplication.
   - **Bytes 4+**: Raw UTF-8 bytes containing the actual chat text message.

---

## Technology Stack

- **Language**: Kotlin (Android Client), C++ (ESP32 Firmware)
- **UI Framework**: Jetpack Compose (Material Design 3)
- **Architecture**: MVVM (Model-View-ViewModel) + Repository Pattern
- **Local Database**: SQLite (Offline Queue & Contact lists)
- **Compression**: LZ4 (Java)
- **Build System**: Gradle (Kotlin DSL)
- **JDK Target**: JDK 17
- **Target SDK**: Android 16 (API 36)

---

## Repository Structure

```
├── app/
│   ├── src/main/java/com/example/bitchat_lite/
│   │   ├── MainActivity.kt        # Application Entry, Lifecycle Hooks & Edge-to-Edge Setup
│   │   ├── Navigation.kt          # Compose Navigation Routes
│   │   ├── NavigationKeys.kt      # Navigation serializable keys
│   │   ├── bluetooth/
│   │   │   └── BluetoothHandler.kt # BLE advertising, scanning, and GATT operations
│   │   ├── data/
│   │   │   ├── DataRepository.kt   # Data Access Interfaces
│   │   │   ├── DefaultDataRepository.kt # Data repository orchestrating sync & auth
│   │   │   └── OfflineQueueDbHelper.kt  # SQLite database helper for queue and contacts
│   │   ├── data/models/
│   │   │   ├── BluetoothPeer.kt    # Peer data model representing discovered devices
│   │   │   ├── ChatMessage.kt      # Chat message data model
│   │   │   └── ConnectionStatus.kt # Connection state enum
│   │   └── ui/
│   │       ├── chat/              # Chat conversation screen, input row & ViewModel
│   │       ├── contacts/          # Contacts directory screen
│   │       ├── lock/              # Numerical PIN pad verification/setup screens
│   │       ├── main/              # Dashboard home screen with settings drawer
│   │       ├── security/          # Security settings config panel
│   │       └── setup/             # Onboarding setup screen
│   └── build.gradle.kts           # Module-level build script
├── firmware/
│   └── relay.ino                  # ESP32 flooding mesh relay firmware (Arduino IDE)
├── Dummy build/                   # Compiled releases (APKs) directory
├── README.md                      # Main developer guide
├── build.gradle.kts               # Root Gradle project script
└── settings.gradle.kts            # Project settings
```

---

## Building & Running

### Android Application
1. **Prerequisites**: JDK 17, Android SDK Platform 36.
2. **Build command**:
   ```powershell
   $env:JAVA_HOME="c:\Users\Ricky\Desktop\BlueMesh\.jdk\jdk-17.0.19+10"
   .\gradlew.bat assembleDebug
   ```
   The output APK resides in `app/build/outputs/apk/debug/app-debug.apk`.

### ESP32 Firmware
1. Open `firmware/relay.ino` in the Arduino IDE.
2. Install the standard ESP32 board support packages (v2.x or higher).
3. Set your target board (e.g., ESP32 Dev Module).
4. Compile and upload to your ESP32 hardware node.

---

## Development & Code Style

- All mobile client source files are written in **Kotlin**.
- ESP32 firmware is written in **C++** utilizing the standard `<BLEDevice.h>` core stacks.
- Maintain edge-to-edge drawing capabilities and system bar padding configurations on all Compose UI screens.
- Use `WindowInsets.safeDrawing.exclude(WindowInsets.ime)` combined with `.imePadding()` on text input containers to avoid double-padding.
