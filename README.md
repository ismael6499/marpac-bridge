# MarpacBridge 🔊💤

[![GitHub Release](https://img.shields.io/github/v/release/ismael6499/marpac-bridge?color=blue&style=flat-square)](https://github.com/ismael6499/marpac-bridge/releases)
[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square)](https://android.com)
[![API](https://img.shields.io/badge/API-26%2B-orange?style=flat-square)](https://android.com)
[![Protocol](https://img.shields.io/badge/Protocol-BLE%20GATT-blueviolet?style=flat-square)](https://www.bluetooth.com/)
[![License](https://img.shields.io/badge/License-MIT-purple?style=flat-square)](LICENSE)

**MarpacBridge** is an open-source, offline-first Android companion app and automation bridge engineered to control **Marpac / Yogasleep** Bluetooth Low Energy sound machines (such as Dohm Connect and Yogaconnect devices).

Designed for users seeking reliable control without proprietary cloud accounts, tracking, or network dependencies, MarpacBridge provides a clean native interface, Android Quick Settings tile support, exact background schedule automation with boolean logic, and an extensible Intent API for Tasker and smartwatch automation.

---

## ✨ Features

### 📡 Direct Offline BLE Control
- Direct Bluetooth Low Energy (GATT) communication between your Android device and the sound machine.
- **100% Offline & Private**: Zero registration, no cloud telemetry, and no internet access required.
- Robust BLE command queue with automated re-connection, packet throttling (300ms inter-op delay), and state synchronization.

### 🎛️ Complete Device Management
- Instant **Power ON / OFF** toggle.
- Discrete **Fan Speed / Volume Level (1–10)** regulation with real-time hardware feedback.
- Live status badge monitoring BLE connection state and machine telemetry.

### ⏰ Advanced Schedule & Condition Engine
- **Hardware & Local Plans**: Sync schedules directly to the sound machine's internal memory or manage flexible local software alarms.
- **Nested Boolean Logic**: Compose advanced conditional rules using `AND`, `OR`, parentheses, and condition cards.
- **State-Aware Triggers**: Evaluate preconditions before triggering (e.g. only adjust volume if machine is currently ON and volume is above level 4).
- **Battery-Friendly Exact Alarms**: Powered by `AlarmManager.setExactAndAllowWhileIdle` and `ELAPSED_REALTIME_WAKEUP` to ensure reliable execution without polluting system alarm widgets.

### ⚡ Android Quick Settings Tile
- Integrated `MarpacTileService` providing a one-tap Quick Settings tile.
- Toggle machine power directly from the Android status bar or lock screen without opening the app.

### 🤖 Tasker & Automation API
- Broadcast receiver (`MarpacReceiver`) listening on `com.nitsu.marpacbridge.ACTION_CONTROL`.
- Full bidirectional synchronization with **Tasker**, **Automate**, **MacroDroid**, and Android Wear / Galaxy Watch widgets.
- Broadcasts `ACTION_STATE_CHANGED` and `ACTION_STATE_RESPONSE` with current state payload (`power`, `power_text`, `volume`, `last_update`).

---

## 📲 Automation Intent Reference (Tasker / MacroDroid)

Send an Android Broadcast Intent with the action:
```
com.nitsu.marpacbridge.ACTION_CONTROL
```

### Supported Extras:

| Extra String | Type | Values / Description |
|---|---|---|
| `command` | `String` | `"power"`, `"level"`, `"volume"`, `"get_state"` |
| `state` | `int` | `1` (Power ON), `0` (Power OFF) |
| `level` or `volume` | `int` | `1` to `10` (Desired fan speed / volume) |

### Examples via ADB:

**Turn Power ON:**
```bash
adb shell am broadcast -a com.nitsu.marpacbridge.ACTION_CONTROL --es command "power" --ei state 1
```

**Set Speed / Volume to Level 6:**
```bash
adb shell am broadcast -a com.nitsu.marpacbridge.ACTION_CONTROL --es command "level" --ei level 6
```

**Turn Power OFF:**
```bash
adb shell am broadcast -a com.nitsu.marpacbridge.ACTION_CONTROL --es command "power" --ei state 0
```

**Query Current State:**
```bash
adb shell am broadcast -a com.nitsu.marpacbridge.ACTION_CONTROL --es command "get_state"
```
*(The app will respond with `com.nitsu.marpacbridge.ACTION_STATE_RESPONSE` containing `power` and `volume` extras.)*

---

## 📥 Installation

1. Download the latest `MarpacBridge-vX.X.X.apk` from the [Releases](https://github.com/ismael6499/marpac-bridge/releases) page.
2. Install the APK on your Android device (Android 8.0 Oreo / API 26 or higher):
   ```bash
   adb install -r MarpacBridge-v1.0.0.apk
   ```
3. Open the app, grant Bluetooth / Nearby Devices permissions, scan for your sound machine, and pair.

---

## 🛠️ Architecture

```
MarpacBridgeApp/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/nitsu/marpacbridge/
│   │   │   ├── BLEService.java             # Foreground service managing GATT BLE lifecycle & command queue
│   │   │   ├── MainActivity.java           # Controls UI, device pairing, condition builder & plan editor
│   │   │   ├── MarpacReceiver.java         # Broadcast receiver for Tasker / external automation intents
│   │   │   ├── MarpacTileService.java      # Android Quick Settings tile service
│   │   │   └── ScheduleAlarmReceiver.java  # Exact AlarmManager receiver with boolean logic evaluator
│   │   └── res/                            # Neon icon, layouts, colors, drawables
│   └── build.gradle                        # Application build configuration (Java 17, SDK 34)
├── gradle/wrapper/                         # Gradle 8.4 wrapper distribution
└── settings.gradle
```

---

## 🏗️ Building from Source

### Prerequisites
- JDK 17 or JDK 21
- Android SDK (compileSdk 34, minSdk 26)

### Clone & Build
```bash
git clone https://github.com/ismael6499/marpac-bridge.git
cd marpac-bridge/MarpacBridgeApp
```

Compile and assemble the debug APK:
```bash
# On Linux / macOS:
./gradlew assembleDebug

# On Windows:
.\gradlew.bat assembleDebug
```

The compiled APK will be located at:
`MarpacBridgeApp/app/build/outputs/apk/debug/app-debug.apk`

---

## 🤝 Contributing

Contributions, bug reports, and enhancements are welcome!
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/MyFeature`)
3. Commit your changes (`git commit -m 'feat: add MyFeature'`)
4. Push to the branch (`git push origin feature/MyFeature`)
5. Open a Pull Request

---

## 📄 License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.
