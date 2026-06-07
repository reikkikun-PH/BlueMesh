# Programming Languages Used in This Codebase

## Overview

This is an **Android application** project built primarily with **Kotlin**.

---

## Primary Languages

### 1. Kotlin (`.kt` files) - Main Application Code

**Purpose:** Primary programming language for all application logic, UI, and business logic.

**Key Files:**
- `MainActivity.kt` - Main application entry point
- `BluetoothHandler.kt` - Bluetooth communication handler
- `DataRepository.kt` - Data layer/repository pattern
- `BluetoothPeer.kt` - Bluetooth peer data model
- `ChatMessage.kt` - Chat message data model
- `ConnectionStatus.kt` - Connection status model
- `Navigation.kt`, `NavigationKeys.kt` - Navigation logic
- `ChatScreen.kt`, `ChatScreenViewModel.kt` - Chat UI and ViewModel
- `MainScreen.kt`, `MainScreenViewModel.kt` - Main UI and ViewModel
- `SetupScreen.kt` - Setup UI
- `Theme.kt`, `Color.kt`, `Type.kt` - Material Design theming
- `MainScreenTest.kt` - UI tests
- `MainScreenViewModelTest.kt` - ViewModel tests

**Features Used:**
- MVVM architecture pattern
- Jetpack Compose UI framework (implied by ViewModel usage)
- Bluetooth Low Energy (BLE) integration
- Repository pattern for data management
- Material Design 3 theming

---

### 2. Kotlin DSL (`.kts` files) - Build Configuration

**Purpose:** Gradle build scripts written in Kotlin instead of Groovy.

**Key Files:**
- `build.gradle.kts` - Root project build configuration
- `settings.gradle.kts` - Project settings and dependency management
- `app/build.gradle.kts` - Application module build configuration

**Benefits:**
- Type-safe build configuration
- Better IDE support
- Enhanced code completion
- Compile-time error checking

---

### 3. XML - Android Resources

**Purpose:** Android resource configuration and asset management.

**Key Files:**
- `AndroidManifest.xml` - Application manifest
- `strings.xml` - String resources
- `themes.xml` - Theme customization
- `drawable/` - Drawable resources
- `mipmap-anydpi-v26/` - App launcher icons
- `values/` - Resource values (colors, dimensions, etc.)
- `xml/` - XML configuration files (backup rules, data extraction)

---

## Technology Stack Summary

| Category | Technology |
|----------|------------|
| **Language** | Kotlin |
| **Platform** | Android |
| **UI Framework** | Jetpack Compose (inferred) |
| **Architecture** | MVVM (Model-View-ViewModel) |
| **Build System** | Gradle (Kotlin DSL) |
| **Design System** | Material Design 3 |
| **Hardware** | Bluetooth Low Energy (BLE) |

---

## Notes

- No Java source files found (only build artifacts in `build/` directory)
- Project uses modern Android development practices
- Codebase follows clean architecture principles with separation of concerns
