#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Отправка позиции по протоколу MT60 (Megastek Meitrack).
Устройства:
    Megastek MT60
    Megastek MT60-X
    Megastek MT-90x
    Megastek MT60PRO
    Megastek MT65
    Megastek MT100
    Megastek MT110
    Megastek MT200x
    Megastek MT300
    Megastek MT500
    Megastek GPT-69
    Megastek GT-89
    Megastek GT-99
    Megastek XT-007
    Megastek XT7
    Megastek S20
    Megastek S30
    Megastek PT60
    Megastek GMT-368
    Megastek GMT368s
    Megastek GMT-368SQ
    Megastek GVT-369
    Megastek GVT-390
    Megastek GVT-500
    Megastek GVT-510
    Megastek GVT430
    Megastek GVT800
    Oyster GPS-Tracker (MT60 clone)
    GoTop TL-201
    GoTop TL-202
    GoTop TL-206
    Topshine TLT-1A
    Topshine TLT-1B
    Topshine TLT-1C
    Topshine TLT-1D
    Topshine TLT-2H
    Topshine TLT-2K
    Topshine TLT-2N
    Topshine TLT-3A

    
Формат (Mt60):
  sendDataPacket:  "$MGV002," + <данные>
  createDataPacket (порядок полей, Mt60.java:32-99):
    $MGV002,<imei>,<deviceName>,<realMode>,ddMMyy,HHmmss,A/V,
      <lat_NMEA>,<NS>,<lon_NMEA>,<EW>,00,<sats>,00,<hdop>,
      <speed_узлы>,<heading>,<alt>,,<mcc>,<mnc>,<lac>,<cell>,<gsm>,
      ,,,,,,,,00,<battery>,<alert>;!

  - скорость в УЗЛАХ: м/с * 3.6 / 1.852  (Mt60.java:76)
  - альт в метрах, hdop = accuracy/8 (или 0.0)
  - alert: "Sos" | "Timer"
  - батарея в процентах (getBatteryPercentage)

Формат IMEI: произвольная строка (обычно цифровой IMEI или hex-ID).
У MT60: imei и deviceName равны (sendDataPacket передаёт один и тот же).

Пример пакета:
    $MGV002,13c438cdd3b76992,13c438cdd3b76992,S,081126,160737,A,5545.2358,N,03737.2474,E,00,12,00,0.00,24.51,61,251,,250,1,1234,56789,75,,,,,,,,,,00,78,Timer;!

Поля:
    $MGV002,<imei>,<deviceName>,<realMode>,<ddMMyy>,<HHmmss>,<A/V>,
             <lat_NMEA>,<NS>,<lon_NMEA>,<EW>,00,<sats>,00,<hdop>,
             <speed_узлы>,<heading>,<alt>,,<mcc>,<mnc>,<lac>,<cell>,<gsm>,
             <empty x9>,00,<battery>,<alert>;!
  - realMode: "S" (статичн.) / "E"/"T" (мониторинг); A/V — валидность GPS
  - speed — в узлах; 9 пустых полей между gsm и "00" (Mt60.java:94)
  - alert: "Timer" (по таймеру) / "Sos"; замыкание ";!"

Ограничения и особенности:
  - пакет начинается с "$MGV002," (версия протокола в маркере нет);
  - одно TCP-соединение, читается ответ до "\\r\\n";
  - успех только при ответе ровно "DATA ACCEPT" (SendHelper.java:382);
  - координаты NMEA DDMM.mmmm (00 / 000-ширина), не десятичные.

Соответствие дизассемблингу:
  - Mt60.java (createDataPacket / sendDataPacket)
  - ConnectionManager.java (сокет и чтение ответа)
  - SendHelper.java:382 (проверка DATA ACCEPT)
"""

import argparse
import random
import socket
import time


# ===== MOCK-ПАРАМЕТРЫ (заглушки трекера) =====
MOCK_HOST = "vega.livegpstracks.ru"
MOCK_PORT = 3339
MOCK_IMEI = "13c438cdd3b76992"
MOCK_DEVICE_NAME = "13c438cdd3b76992"
MOCK_REAL_MODE = "S"             # S - статичный режим отправки
MOCK_ALERT = "Timer"
MOCK_LAT = 55.55              # Москва
MOCK_LON = 37.55
MOCK_SPEED_MIN = 10.0            # км/ч
MOCK_SPEED_MAX = 60.0
MOCK_BEARING_MIN = 0.0
MOCK_BEARING_MAX = 360.0
MOCK_ALT = 251.0
MOCK_SATS = 12
MOCK_BATTERY = 78               # %
MOCK_HDOP = 0.0                  # accuracy/8 (или 0)
MOCK_GSM = 75


def nmea_lat(lat):
    lat = abs(lat)
    d = int(lat)
    m = (lat - d) * 60
    return "{:02d}{:07.4f}".format(d, m)


def nmea_lon(lon):
    lon = abs(lon)
    d = int(lon)
    m = (lon - d) * 60
    return "{:03d}{:07.4f}".format(d, m)


def minutes(days_sec):
    return int(days_sec // 60)


def build_data_packet(imei, device_name, real_mode, lat, lon, speed_kmph,
                      heading, altitude, satellites, battery, hdop, gsm,
                      alert, mcc=250, mnc=1, lac=1234, cell=56789):
    """Собирает пакет $MGV002,... (Mt60.createDataPacket)."""
    t = time.gmtime()
    # скорость: м/с * 3.6 / 1.852 => узлы. Вход уже км/ч => *1000/3600 м/с => м/с*3.6/1.852 = км/ч/1.852
    speed_knots = speed_kmph / 1.852

    fields = [
        imei, device_name, real_mode,
        time.strftime("%d%m%y", t),
        time.strftime("%H%M%S", t),
        "A",                      # valid
        nmea_lat(lat), "N",
        nmea_lon(lon), "E",
        "00",                     # (const)
        str(satellites),
        "00",                     # (const)
        "{:.2f}".format(hdop),
        "{:.2f}".format(speed_knots),   # узлы
        "{:.0f}".format(heading),
        "{:.0f}".format(altitude),
        "",                       # (blank - before mcc)
        str(mcc), str(mnc), str(lac), str(cell),
        str(gsm),
        # 9 пустых полей (Mt60.java:94 ",,,,,,,,,00,")
        "", "", "", "", "", "", "", "", "",
        "00",
        str(battery),
        alert,
    ]
    return "$MGV002," + ",".join(fields) + ";!"


def send_tcp(host, port, payload, timeout=10.0, wait_s=1.0):
    print("----- TCP SEND -----")
    print(payload)
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
        sock.sendall((payload + "\r\n").encode("utf-8"))
        full = b""
        for _ in range(3):
            try:
                import select
                ready, _, _ = select.select([sock], [], [], wait_s)
                if not ready:
                    continue
                data = sock.recv(1024)
                if not data:
                    break
                full += data
                if b"\r\n" in full:
                    break
            except (socket.timeout, BlockingIOError):
                continue
        sock.close()
        text = full.decode("utf-8", errors="replace")
        print("----- TCP RECV -----")
        print(repr(text) if text else "(no response)")
        return text
    except OSError as e:
        print("Connection error: {}".format(e))
        return ""


def main():
    ap = argparse.ArgumentParser(description="Эмуляция MT60 (Megastek): отправка позиции по TCP")
    ap.add_argument("--host", default=MOCK_HOST, help="сервер (mock: {})".format(MOCK_HOST))
    ap.add_argument("--port", type=int, default=MOCK_PORT, help="порт (mock: {})".format(MOCK_PORT))
    ap.add_argument("--imei", default=MOCK_IMEI, help="IMEI (mock: {})".format(MOCK_IMEI))
    ap.add_argument("--lat", type=float, default=MOCK_LAT, help="широта (mock: Москва)")
    ap.add_argument("--lon", type=float, default=MOCK_LON, help="долгота (mock: Москва)")
    ap.add_argument("--speed", type=float, default=None,
                    help="скорость, км/ч (по умолч. случайная из mock-диапазона)")
    ap.add_argument("--heading", type=float, default=None,
                    help="курс, градусы (по умолч. случайный из mock-диапазона)")
    ap.add_argument("--alt", type=float, default=MOCK_ALT, help="высота, м (mock: {})".format(MOCK_ALT))
    ap.add_argument("--sats", type=int, default=MOCK_SATS, help="спутники (default {})".format(MOCK_SATS))
    ap.add_argument("--alert", default=MOCK_ALERT, choices=["Timer", "Sos"], help="тип алерта")
    args = ap.parse_args()

    speed = args.speed if args.speed is not None else round(random.uniform(MOCK_SPEED_MIN, MOCK_SPEED_MAX), 1)
    heading = args.heading if args.heading is not None else round(random.uniform(MOCK_BEARING_MIN, MOCK_BEARING_MAX), 1)

    print("IMEI: {}\nlat={} lon={} speed={} km/h heading={} deg".format(
        args.imei, args.lat, args.lon, speed, heading))

    pkt = build_data_packet(args.imei, MOCK_DEVICE_NAME, MOCK_REAL_MODE,
                            args.lat, args.lon, speed, heading, args.alt,
                            args.sats, MOCK_BATTERY, MOCK_HDOP, MOCK_GSM, args.alert)
    answer = send_tcp(args.host, args.port, pkt)
    if answer.strip().upper() == "DATA ACCEPT":
        print("=> MT60 accepted (DATA ACCEPT) — packet delivered")
    else:
        print("=> server answer: {!r}".format(answer.strip()))


if __name__ == "__main__":
    main()