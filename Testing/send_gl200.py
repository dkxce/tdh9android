#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Отправка позиции по протоколу GL200 / Queclink.
Устройства:
    Queclink GL100
    Queclink GL100M
    Queclink GL200
    Queclink GL300
    Queclink GL300M
    Queclink GL300W
    Queclink GL300MA
    Queclink GL310M
    Queclink GL320M
    Queclink GL500
    Queclink GL500M
    Queclink GL505
    Queclink GL520
    Queclink GL521M
    Queclink GL530

Формат (Gl200):
  sendDataPacket:  "+RESP:GTFRI,301201," + data + "\r\n"
  createDataPacket (поля):
    <imei>,,0,0,1,<accuracy>,<speed_км/ч>,<heading>,<altitude>,<lon>,<lat>,
      <yyMMddHHmmss>,<mcc>,<mnc>,<lac>,<cell>,,<battery%>,<yyMMddHHmmss>,<rand_hex>$
  - accuracy — location.getAccuracy() (или "0")
  - скорость — КМ/Ч: м/с * 3.6 (Gl200.java:40)
  - lat/lon — ДЕСЯТИЧНЫЕ градусы (не NMEA), Gl200.java:49/54
  - версия протокола "301201" (Gl200.java:15)
  - замыкание: последний hex из Random().nextInt(65536) + "$"
  Ответ игнорируется приложением (Gl200.sendDataPacket возвращает "").

Формат IMEI: произвольная строка (обычно цифровой IMEI или hex-ID).

Пример пакета:
    +RESP:GTFRI,301201,13c438cdd3b76992,,0,0,1,0,45.40,61,251.0,37.620790,55.753930,261108162527,250,1,1234,56789,,78,261108162527,9d7c$

Поля (по Gl200.java:23-77):
    +RESP:GTFRI,<ver=301201>,<imei>,,0,0,1,<accuracy>,<speed_км/ч>,<heading>,<alt>,
      <lon>,<lat>,<yyMMddHHmmss>,<mcc>,<mnc>,<lac>,<cell>,,<battery%>,
      <yyMMddHHmmss>,<rand_hex>$
  - 0,0,1 — константы; пустые поля — deviceId/серийник (в приложении "")
  - lat/lon — ДЕСЯТИЧНЫЕ градусы; speed — км/ч; alt/accuracy
  - battery — проценты; rand_hex — hex Random(65536)+"$"

Ограничения и особенности:
  - пакет начинается с "+RESP:" и версии "301201";
  - координаты ДЕСЯТИЧНЫЕ (не NMEA) — отличие от MT60/TK102/Wialon;
  - ответ сервера приложение ИГНОРИРУЕТ (Gl200.sendDataPacket возвращает ""),
    т.е. успех определяется тем, что сокет не упал;
  - одно TCP-соединение.

Соответствие дизассемблингу:
  - Gl200.java (createDataPacket / sendDataPacket)
  - ConnectionManager.java (сокет)
"""

import argparse
import random
import socket
import time


# ===== MOCK-ПАРАМЕТРЫ (заглушки трекера) =====
MOCK_HOST = "vega.livegpstracks.ru"
MOCK_PORT = 3359
MOCK_IMEI = "13c438cdd3b76992"
MOCK_LAT = 55.55            # Москва
MOCK_LON = 37.55
MOCK_SPEED_MIN = 10.0
MOCK_SPEED_MAX = 60.0
MOCK_BEARING_MIN = 0.0
MOCK_BEARING_MAX = 360.0
MOCK_ALT = 251.0
MOCK_ACCURACY = 0.0
MOCK_BATTERY = 78
MOCK_MCC = 250
MOCK_MNC = 1
MOCK_LAC = 1234
MOCK_CELL = 56789


def build_data_packet(imei, lat, lon, speed_kmph, heading, altitude,
                      battery, mcc=250, mnc=1, lac=1234, cell=56789,
                      accuracy=0.0):
    """Собирает +RESP:GTFRI,301201,...$ (Gl200.createDataPacket)."""
    now = time.time()
    t = time.gmtime(now)
    utc = time.strftime("%y%m%d%H%M%S", t)
    rand_hex = "{:x}".format(random.randint(0, 65535))

    fields = [
        imei,
        "", "0", "0", "1",
        "{:.0f}".format(accuracy) if accuracy else "0",
        "{:.2f}".format(speed_kmph),
        "{:.0f}".format(heading),
        "{:.1f}".format(altitude) if altitude else "0",
        "{:.6f}".format(lon),   # долгота десятичная
        "{:.6f}".format(lat),   # широта десятичная
        utc,
        str(mcc), str(mnc), str(lac), str(cell),
        "",
        str(battery),
        utc,
        rand_hex + "$",
    ]
    return "+RESP:GTFRI,301201," + ",".join(fields) + "\r\n"


def send_tcp(host, port, payload, timeout=10.0, wait_s=1.0):
    print("----- TCP SEND -----")
    print(payload.rstrip("\r\n"))
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
        sock.sendall(payload.encode("utf-8"))
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
    ap = argparse.ArgumentParser(description="Эмуляция GL200 (Queclink): отправка позиции по TCP")
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
    ap.add_argument("--accuracy", type=float, default=MOCK_ACCURACY, help="точность (mock: {})".format(MOCK_ACCURACY))
    ap.add_argument("--batt", type=int, default=MOCK_BATTERY, help="батарея %% (mock: {})".format(MOCK_BATTERY))
    args = ap.parse_args()

    speed = args.speed if args.speed is not None else round(random.uniform(MOCK_SPEED_MIN, MOCK_SPEED_MAX), 1)
    heading = args.heading if args.heading is not None else round(random.uniform(MOCK_BEARING_MIN, MOCK_BEARING_MAX), 1)

    print("IMEI: {}\nlat={} lon={} speed={} km/h heading={} deg".format(
        args.imei, args.lat, args.lon, speed, heading))

    pkt = build_data_packet(args.imei, args.lat, args.lon, speed, heading, args.alt,
                            args.batt, accuracy=args.accuracy)
    answer = send_tcp(args.host, args.port, pkt)


if __name__ == "__main__":
    main()