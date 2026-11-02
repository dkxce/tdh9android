# Tidradio TD-H9 RadioBridge · TNC через BLE на Android

[RU](README.md) | [EN](READEME.en.md) | [Releases](https://github.com/dkxce/tdh9android/releases)    

Мост между рацией **TIDRadio TD-H9** (BLE GATT сервис Odmaster `af00`) и сетью
**TCP/IP** с тремя протоколами. Рация выступает как аппаратный KISS-TNC: телефон
подключается по BLE и обменивается с рацией KISS/AX.25-кадрами, а приложение
поднимает локальные TCP-серверы, к которым подключаются APRS-приложения.

**Версия:** 1.0.0

## Возможности

- **BLE-подключение** к рации как к KISS-TNC (service `0000af00`, write `af01`,
  notify `af02`; запасные UUID `ff02`/`ffe2` и `ff01`/`ffe1`).
- **Три TCP-сервера** (один одновременно, выбор режима):
  - **Binary KISS** — порт 8023 (сырые KISS-фреймы, как последовательный TNC по сети);
  - **AGWPE (Dire Wolf)** — порт 8000 (36-байтные заголовки, для UISS/UI-View32/APRSIS32);
  - **APRS-IS** — порт 14580 (текстовый протокол + проверка passcode).
- **Foreground-сервис**: соединение и серверы работают с выключенным экраном.
- Встроенные кодеры/декодеры **KISS** и **AX.25** (бинарный ⇄ текст).
- Лог RX/TX, список клиентов, настройки (SharedPreferences).

## Требования

- Android Studio (Ladybug 2024.2.1+ рекомендуются).
- JDK 17.
- Устройство Android 8.0+ (API 26).
- Рация TIDRadio TD-H9 или прошивка с APRS BLE-сервисом.

## Сборка

1. Откройте папку `android-bridge` в Android Studio («Open»).
2. Дождитесь первого Gradle Sync (скачает Gradle 8.9 и зависимости).
3. Подключите телефон с включённой отладкой по USB.
4. Нажмите **Run** (🔨). На телефоне выдайте разрешения Bluetooth и, при
   запросе, уведомление (Android 13+).

Если Gradle не синхронизируется, обновите версии плагинов в `build.gradle.kts`
и `com.android.application`/`org.jetbrains.kotlin.android` до используемых в вашей
Android Studio.

## Использование

1. Откройте вкладку **«Рация»**, нажмите **Сканировать**, выберите рацию из
   списка → **подключитесь**. Подключение BLE установится, появится список
   сервисов/GATT-характеристик.
2. Перейдите во вкладку **«Сервер»**: выберите режим (KISS/AGW/APRS-IS), порт,
   при необходимости параметры APRS-IS, нажмите **«Применить и запустить»**.
3. Подключите APRS-приложение к `127.0.0.1:<порт>` телефона (через тот же Wi-Fi
   или хотспот телефона — реальный адрес хоста).
4. Принятые с радио пакеты уходят всем клиентам; отправленные клиентами кадры
   уходят в рацию по BLE.

### Совместимость с APRS-приложениями

| Приложение | Протокол | Порт |
|---|---|---|
| **UISS** | AGWPE TCP/IP | 8000 |
| **UI-View32** | AGWPE TCP/IP | 8000 |
| **APRSIS32** | AGWPE (или Binary KISS) | 8000 / 8023 |
| [**KMZViewer**](https://github.com/dkxce/KMZViewer) | KISS / AGW / APRS-IS | по настройке |
| [**APRSWin**](https://https://github.com/dkxce/APRSWin) | Binary KISS / AGW | 8023 / 8000 |
| [**APRSAir**](https://github.com/dkxce/APRSAIR) | KISS / AGW / APRS-IS | по настройке |

> Программы, умеющие **AGWPE** — ставьте режим AGW (8000); ожидающие **KISS-TNC**
> по TCP — Binary KISS (8023); сетевые APRS-IS — APRS-IS (14580).

## Структура проекта

```
app/src/main/java/com/dkxce/radio_bridge/
  MainActivity.kt            — разрешения, запуск сервиса, Compose
  app/AppModel.kt            — настройки (SharedPreferences) + UI-состояние
  app/MainViewModel.kt       — ViewModel
  ble/BleBridge.kt           — GATT-мост, скан/подключение/write/notify
  core/Kiss.kt               — KISS-фрейминг и парсер
  core/Ax25.kt               — бинарный AX.25 ⇄ APRS-текст
  net/BaseTcpServer.kt       — общий TCP-сервер
  net/KissTcpServer.kt       — Binary KISS
  net/AgwTcpServer.kt        — AGWPE/Dire Wolf
  net/AprsIsTcpServer.kt     — APRS-IS + passcode
  service/BridgeService.kt   — foreground-сервис, связка BLE ⇄ TCP
  ui/RadioBridgeApp.kt       — каркас экрана (вкладки)
  ui/Tabs.kt                 — вкладки «Рация/Сервер/Лог»
```

## Замечания

- **Ядро (BLE + KISS + AX.25 + три сервера) полностью перенесено из
  Python-проекта** [dkxce/Tidradio-TD-H9](https://github.com/dkxce/Tidradio-TD-H9)
- Foreground-service типа `connectedDevice` требует на Android 14+ разрешение
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` (заявлено в манифесте).
  
---

**Автор:** dkxce (c) https://github.com/dkxce/Tidradio-TD-H9 · 