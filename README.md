# BlueMesh 📡💬

> A peer-to-peer, decentralized Bluetooth mesh messaging application for Android. Connect, chat, and communicate securely without any internet dependency.

---

## 📱 Features

- 📶 **P2P Mesh Network**: Connect directly to nearby peers to form a local communications mesh.
- 🔐 **Passcode Lock Screen**: Secure numerical PIN lock (SHA-256 protected) enforcing private local access.
- ⏳ **5-Minute Auto-Lock**: Automatically locks the application if it is backgrounded for more than 5 minutes.
- 🗄️ **Persistent Offline Queue**: Local SQLite database storing queued messages when peers are out of range; messages are automatically synchronized once they reappear.
- ⚡ **LZ4 Compression**: Payloads are compressed with high-performance LZ4 compression to optimize Bluetooth bandwidth.
- 📦 **Fragmented MTU Packetizer**: Messages are dynamically packetized based on negotiated BLE MTU size, written sequentially with a 50ms inter-packet delay.
- 📱 **Android 15/16 Immersive Edge-to-Edge**: Full support for native edge-to-edge drawing, rendering backgrounds seamlessly behind transparent status and navigation bars.
- 🎹 **IME Keyboard Inset Compatibility**: Dual-mode input alignment using Compose insets. Excludes keyboard height from root containers via `WindowInsets.safeDrawing.exclude(WindowInsets.ime)` and applies `.imePadding()` directly to inner content columns. This ensures text areas sit perfectly right on top of the keyboard across both AOSP emulators and physically-resizing OEM ROMs.

---

## 🏗️ Technology Stack

- **Language**: 100% Kotlin
- **UI Framework**: Jetpack Compose (Material Design 3)
- **Architecture**: MVVM (Model-View-ViewModel) + Repository Pattern
- **Local Database**: SQLite (Offline Queue & Contact lists)
- **Compression**: LZ4 (Java)
- **Build System**: Gradle (Kotlin DSL)
- **JDK Target**: JDK 17
- **Target SDK**: Android 16 (API 36)

---

## 📁 Repository Structure

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
├── Dummy build/                   # Compiled releases (APKs) directory
├── README.md                      # Main developer guide
├── programming.md                 # Language and compiler guidelines
├── DexStringExtractor.kt          # Auxiliary Kotlin tool for DEX string parsing
├── build.gradle.kts               # Root Gradle project script
└── settings.gradle.kts            # Project settings
```

---

## 🛠️ Building & Running

### Prerequisites
- JDK 17 (pre-packaged JDK is available under the `.jdk/` folder of this workspace)
- Android SDK (Platform 36 / Build-Tools 36.0.0)

### Run Compilation
To compile the debug APK, set `JAVA_HOME` to the pre-packaged JDK 17 path and run the Gradle build script:

```powershell
# Set JDK 17 environment and build
$env:JAVA_HOME="c:\Users\Ricky\Desktop\BlueMesh\.jdk\jdk-17.0.19+10"
.\gradlew.bat assembleDebug
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📜 Development & Code Style

- All source files are strictly written in **Kotlin**.
- Avoid introducing any Java class hybrid structures inside the `app` source module.
- Maintain edge-to-edge drawing capabilities and system bar padding configurations on all Compose UI screens.
- Use `WindowInsets.safeDrawing.exclude(WindowInsets.ime)` combined with `.imePadding()` on text input container layout components to maintain consistent keyboard positions without double-padding.
- Keep comments and docstrings intact for all critical BLE orchestration routines inside `BluetoothHandler.kt`.
