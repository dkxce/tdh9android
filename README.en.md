# TDH9RadioBridge · TNC via BLE on Android

[RU](README.md) | [EN](README.en.md) | [Releases](https://github.com/dkxce/tdh9android/releases)

Bridge between **TIDRadio TD-H9** (BLE GATT service Odmaster 'af00') and the network
**TCP/IP with three protocols. The radio acts as a hardware KISS-TNC: phone
connects via BLE and exchanges with the KISS / AX.25-frame radio, and the application
Raises local TCP servers to which APRS applications are connected.

** Version:** v1.1. 0

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

### Minimum requirements for Android devices

- **OS:** Android **8.0 (API 26)** and higher.
**RAM/CPU:** 1 GB of RAM and single-core CPU (the application works on weak devices as well).
- **Bluetooth:** mandatory **Bluetooth Low Energy (BLE/Bluetooth 4.0+)*
**Enabled geolocation service (GPS/"Location")** - mandatory
BLE-scan on Android <12 (platform ban, without it there is no radio).
**Wi-Fi/LAN** – required to connect external APRS clients to TCP servers
(Telephone and PC on the same network)
**Run permissions:** Bluetooth, Geolocation; Android 13+ notifications.
Optional: storage (for logs/settings) - not critical, data is stored
locally in the application memory.

> **Compatibility check:** Minimum specs: Android 8.0, BLE 4.0
> Geolocation enabled. On devices without geolocation enabled, BLE scanning
> Will not work - turn on "Location" in the settings of the phone.

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

Fast start: how to connect the radio

1. **Enable Bluetooth and GPS (geolocation)** on your phone - BLE scan on
Android requires a location service enabled.
2. ** Turn on the TIDRadio TD-H9 radio and turn on APRS and Bluetooth.
3. C issue permissions **Bluetooth** and **Geolocation** (if not already issued).
4. Open the application **TDH9RadioBridge**, tab **Radio**, click
- Scanning.
5. From the list of found devices, select the radio (or **History** → →
Push on it, and **Connect* happens.
6. In the log, “[BLE] Connected <name> (<address>)” and a list of GATT services will appear.

### Launching the server

1. Go to the tab ** "Server"**.
2. Select ** Mode** (Binary KISS/AGW (AGWPE)/APRS-IS) and **port**.
3. Click ** "Run the server"**. At the bottom will appear **local addresses** type
`192.168.1.5:8023' is the address to be connected.
4. Connect the APRS application to one of these addresses (phone and client -
on the same Wi-Fi network.

Compatibility with APRS applications

| Annex | Protocol | Port |
|---|---|---|
| **UISS**| AGWPE TCP/IP | 8000 |
| **UI-View32** | AGWPE TCP/IP | 8000 |
| **APRSIS32** | AGWPE (or Binary KISS) | 8000 / 8023 |
| [**KMZViewer**](https://github.com/dkxce/KMZViewer) | KISS/AGW/APRS-IS | on setting |
| [**APRSWin**](https://github.com/dkxce/APRSWin) | Binary KISS/AGW | 8023/8000 |
| [**APRSAir**](https://github.com/dkxce/APRSAIR) | KISS/AGW/APRS-IS | as set up |

> Programs that can **AGWPE** - set AGW mode (8000); waiting **KISS-TNC**
> by TCP — Binary KISS (8023); network APRS-IS — APRS-IS (14580).

## Comments

**The kernel (BLE + KISS + AX.25 + three servers) is fully ported from
Python project** [dkxce/Tidradio-TD-H9](https://github.com/dkxce/Tidradio-TD-H9/tree/main/TDH9KISS)
Foreground service type “connectedDevice” requires Android 14+ permission
`FOREGROUND SERVICE CONNECTED DEVICE' (stated in manifesto)

-

**Author**: [dkxce](https://github.com/dkxce)
**Android**: [dkxce/tdh9android](https://github.com/dkxce/tdh9android)
**Desktop**: [dkxce/Tidradio-TD-H9](https://github.com/dkxce/Tidradio-TD-H9)