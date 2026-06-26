# BlueMesh — Build & Development Guide

## Project Structure
- `User Edition/` — Main app (com.example.bluemesh)
- `Volunteers Edition/` — Volunteers variant (com.example.bluemesh.volunteers)
- Both share identical source files; only 6 identity differences (applicationId, app name, etc.)
- `.jdk/jdk-17.0.19+10/` — Shared JDK 17 for building

## Build Commands

### User Edition (debug)
```
cd "User Edition"
$env:JAVA_HOME="C:\Users\Ricky\Desktop\BlueMesh\.jdk\jdk-17.0.19+10"
.\gradlew app:assembleDebug
```

### Volunteers Edition (debug)
```
cd "Volunteers Edition"
$env:JAVA_HOME="C:\Users\Ricky\Desktop\BlueMesh\.jdk\jdk-17.0.19+10"
.\gradlew app:assembleDebug
```

## Version Bumping & Release
**CRITICAL: Version MUST be bumped BEFORE every build, without exception. Never reuse the same version number.**

On every update or change, always:
1. Read current `versionName`/`versionCode` from either `build.gradle.kts`
2. Increment `versionCode` by 1; increment `versionName` to the next minor version (e.g. 29.24 → 29.25)
3. Update both `User Edition/app/build.gradle.kts` and `Volunteers Edition/app/build.gradle.kts`
4. Update `C:\Users\Ricky\Desktop\BlueMesh Creds\changelog.md` with the new version entry
5. Build both editions
6. Copy APKs to `Dummy build/` dir with format: `app-v{version}-{Edition}-{Description}-debug.apk`

## APK Output (build)
- `User Edition/app/build/outputs/apk/debug/app-debug.apk`
- `Volunteers Edition/app/build/outputs/apk/debug/app-debug.apk`

## APK Output (Dummy build)
- `User Edition/Dummy build/app-v{version}-User-{Description}-debug.apk`
- `Volunteers Edition/Dummy build/app-v{version}-Volunteers-{Description}-debug.apk`

## Mirroring Changes
When modifying source files in User Edition, copy modified files to Volunteers Edition:
```
Copy-Item "User Edition/app/src/main/java/.../File.kt" "Volunteers Edition/app/src/main/java/.../File.kt" -Force
```

## Key Source Files
- `bluetooth/PacketBroadcaster.kt` — BLE write/notification logic, per-address mutexes
- `bluetooth/ConnectionTracker.kt` — Tracks peers, connections, set-based collections
- `bluetooth/GattServerManager.kt` — GATT server lifecycle
- `bluetooth/GattClientManager.kt` — GATT client connections
- `bluetooth/BluetoothHandler.kt` — Top-level BLE coordinator
- `bluetooth/Constants.kt` — MAX_CLIENT_CONNECTIONS, timeouts
- `data/DefaultDataRepository.kt` — Send queues, health monitor, UUID reset
