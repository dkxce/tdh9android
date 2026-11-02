# Tidradio TD-H9 RadioBridge · TNC via BLE on Android

[RU](README.md) | [EN](README.en.md) | [Releases](https://github.com/dkxce/tdh9android/releases)

Bridge between **TIDRadio TD-H9** (BLE GATT service Odmaster 'af00') and the network
**TCP/IP** with three protocols. The radio acts as a hardware KISS-TNC: phone
connects via BLE and exchanges with the KISS / AX.25-frame radio, and the application
Raises local TCP servers to which APRS applications are connected.

** Version:** 1.0. 0

## Opportunities

- **BLE connection** to the radio as to KISS-TNC (service `0000af00`, write `af01`,
notify `af02`; spare UUID `ff02`/`ffe2` and `ff01`/`ffe1`.
**Three TCP servers** (one at a time, mode selection):
**Binary KISS** - port 8023 (raw KISS frames, as a serial TNC over the network);
**AGWPE (Dire Wolf)** port 8000 (36-byte headers, for UISS/UI-View32/APRSIS32)
**APRS-IS** port 14580 (text protocol + passcode verification)
**Foreground service**: The connection and servers work with the screen off.
Built-in coders/decoders **KISS** and **AX.25** (binary ный text).
RX/TX Log, Customer List, SharedPreferences

## Requirements

Android Studio (Ladybug 2024.2.1+ is recommended).
- JDK 17.
Android 8.0+ (API 26)
- TIDRadio TD-H9 radio or firmware with APRS BLE service.

##Assembly

1. Open the Android-bridge folder in Android Studio (“Open”).
2. Wait for the first Gradle Sync (downloads Gradle 8.9 and dependencies).
3. Connect the phone with the debugging enabled via USB.
4. Press **Run** (м). On the phone, issue Bluetooth permissions and
Notification (Android 13+)

If Gradle does not sync, update the plugin versions to build.gradle.kts
and `com.android.application`/`org.jetbrains.kotlin.android` to those used in your
Android Studio.

## Used

1. Open the tab ** Radio **, click ** Scan **, select the radio from
The list goes **get in. BLE connection will be established, the list will appear
Services/GATT Characteristics.
2. Go to the tab **"Server"**: select the mode (KISS/AGW/APRS-IS), port,
If you need APRS-IS settings, click ** "Apply and run"**.
3. Connect the APRS app to '127.0.0.1:<port>' of the phone (via the same Wi-Fi)
Hotspot phone - the real address of the host.
4. Radio-accepted packages leave all customers; customer-sent footage
They're on the BLE radio.

Compatibility with APRS applications

| Annex | Protocol | Port |
|---|---|---|
| **UISS**| AGWPE TCP/IP | 8000 |
| **UI-View32** | AGWPE TCP/IP | 8000 |
| **APRSIS32** | AGWPE (or Binary KISS) | 8000 / 8023 |
| [**KMZViewer**](https://github.com/dkxce/KMZViewer) | KISS/AGW/APRS-IS | on setting |
| [**APRSWin**](https://https://github.com/dkxce/APRSWin) | Binary KISS/AGW | 8023/8000 |
| [**APRSAir**](https://github.com/dkxce/APRSAIR) | KISS/AGW/APRS-IS | as set up |

> Programs that can **AGWPE** - set AGW mode (8000); waiting **KISS-TNC**
> by TCP — Binary KISS (8023); network APRS-IS — APRS-IS (14580).

## Comments

**The kernel (BLE + KISS + AX.25 + three servers) is fully ported from
Python project** [dkxce/Tidradio-TD-H9](https://github.com/dkxce/Tidradio-TD-H9/tree/main/TDH9KISS)
Foreground service type “connectedDevice” requires Android 14+ permission
`FOREGROUND SERVICE CONNECTED DEVICE' (stated in manifesto)

-

**Author:** dkxce (c) https://github.com/dkxce/Tidradio-TD-H9 ·