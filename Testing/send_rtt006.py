#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Отправка позиции по протоколу rtt006 — ОСНОВНОЙ протокол Real Time GPS Tracker.

Что это: фирменный (закрытый) текстовый протокол приложения LGT для передачи
координат на сервер по сырому TCP. Реализация — С++/Java классы DataProtocol.java
и ConnectionManager.java (дизассемблинг), SendHelper.java (отправка).

Поддерживаемые устройства: приложение Real Time GPS Tracker (Google Play,
Live GPS Tracking) — работает на любом Android-смартфоне/планшете; протокол это
прошивка самого приложения, поэтому "список устройств" = устройства с этим
приложением. Используется как основной вход (primary server, protocol 0).

Формат фрейма:
    <7-digit-length>rtt006;IMEI;<данные>

  + <7-digit-length> — длина (в БАЙТАХ) всей последующей строки,
    дополненная нулями слева до 7 цифр (SendHelper.java:178-184).
  + "rtt006;"        — маркер протокола (константа PROTOCOL="rtt006").
  + IMEI             — идентификатор устройства (настройка pref_imei).
  + "<данные>"       — формат из getBasePart(): группы через ',' , поля '|'.

Формат IMEI: hex 16 символов

Пример фрейма:
    0000164rtt006;13c438cdd3b76991;A|55.75393|37.62079|36.0|150.0|90.0|20261108|154827|3|1794152907,12|3.0|0.38|1,0|250|1|1234|56789|75,78|1|31.0|1|0|1|Tele2,300|0|0|0|5.1.0,,

Основная позиционная часть (getBasePart):
    <тип>|<lat>|<lon>|<speed>|<alt>|<dir>|<date>|<time>|<tz>|<unixtime>,
    <sats>|<accuracy>|<hdop>|<gps>,
    <net_type>|<mcc>|<mnc>|<lac>|<cellid>|<gsm>,
    <batt>|<charge>|<batt_temp>|<inet>|<conn_type>|<admin>|<net_name>,
    <send_interval>|<custom_send>|<sos>|<fake>|<app_version>,,

Поля:
  тип   = "A" (фикс) / "P" (ошибка)
  lat   = ДЕСЯТИЧНЫЕ градусы (не NMEA)
  lon   = ДЕСЯТИЧНЫЕ градусы
  speed = км/ч (м/с * 3.6)
  alt   = высота, м
  dir   = курс, град
  date  = yyyyMMdd (UTC), time = HHmmss (UTC), tz = часовой пояс, unixtime = unix-с
  sats/accuracy/hdop(=accuracy/8)/gpsstatus
  net_type|MCC|MNC|LAC|CellID|GSM-сигнал
  batt(уровень)|charge|batt_temp|internet|conn_type|admin|network_name
  send_interval|custom_send|sos|fake|app_version

Ограничения и особенности:
  - одно TCP-соединение, пакет завершается подтверждением сервера до "\\r\\n";
  - признак успеха ответа: содержит "0","1","3","4" (SendHelper.isSendSuccessful);
    "2"->SMS-ветка не считается успехом; "3"/"4" дополнительно ставят тариф;
  - до 20 позиций за пакет, объединяются через ';' (sendDataPacket кэша);
  - длина считается по байтам UTF-8 хвоста; 7 цифр слева, ведущие нули.
  - ответ сервера читается до "\\r\\n" (ConnectionManager.readData).

Соответствие дизассемблингу:
  - DataProtocol.java  ->  функции build_data / getBasePart
  - ConnectionManager.java -> read/write socket
  - SendHelper.java     -> сборка фрейма rtt006 + isSendSuccessful
"""

import argparse
import random
import socket
import time


# ===== MOCK-ПАРАМЕТРЫ (заглушки трекера, из send_bbg.py) =====
# Сервер и порт по умолчанию — из реального приложения Real Time GPS Tracker:
#   ServerMonitoring.java:133  mainPort = "3349"
#   resources/res/values/arrays.xml  RU: vega.livegpstracks.ru / 89.248.193.215
MOCK_HOST = "vega.livegpstracks.ru"
MOCK_PORT = 3349
MOCK_IMEI = "13c438cdd3b76992"
MOCK_LAT = 55.75393          # Москва, Красная площадь
MOCK_LON = 37.62079
MOCK_SPEED_MIN = 10.0        # км/ч
MOCK_SPEED_MAX = 60.0
MOCK_BEARING_MIN = 0.0       # курс
MOCK_BEARING_MAX = 360.0
MOCK_ALT = 251.0             # высота, м
MOCK_PACKET_TYPE = "A"
MOCK_APP_VERSION = "5.1.0"


def unix_now():
    return int(time.time())


def build_data(lat, lon, speed_kmph, course, altitude, packet_type):
    """Собирает '<данные>' из getBasePart() (DataProtocol.java:392)."""
    t = time.gmtime()
    return (
        "{pt}|{lat}|{lon}|{spd:.1f}|{alt}|{dir:.1f}|{date}|{tm}|{tz}|{unixt},"
        "{sats}|{acc}|{hdop:.2f}|{gps},"
        "{net}|{mcc}|{mnc}|{lac}|{cell}|{gsm},"
        "{batt}|{charge}|{batt_temp}|{inet}|{inet_type}|{admin}|{net_name},"
        "{interval}|{custom}|{sos}|{fake}|{ver},,"
    ).format(
        pt=packet_type,
        lat=format(lat, ".5f"),
        lon=format(lon, ".5f"),
        spd=speed_kmph,
        alt=format(altitude, ".1f"),
        dir=course,
        date=time.strftime("%Y%m%d", t),
        tm=time.strftime("%H%M%S", t),
        tz="3",
        unixt=unix_now(),
        # 2-я группа: спутники|точность|hdop|gps-статус
        sats="12", acc="3.0", hdop=3.0 / 8.0, gps="1",
        # 3-я группа: тип сети|MCC|MNC|LAC|CellID|GSM-сигнал
        net="0", mcc="250", mnc="1", lac="1234", cell="56789", gsm="75",
        # 4-я группа: батарея|заряд|temptemp|интернет|тип|admin|сеть
        batt="78", charge="1", batt_temp="31.0", inet="1",
        inet_type="0", admin="1", net_name="Tele2",
        # 5-я группа: интервал|custom|sos|fake|версия
        interval="300", custom="0", sos="0", fake="0", ver=MOCK_APP_VERSION,
    )


def build_frame(imei, data):
    """'<7-digit-length>rtt006;IMEI;<данные>' с индивидуальным расчётом длины."""
    body = "rtt006;{imei};{data}".format(imei=imei, data=data)
    length = str(len(body.encode("utf-8")))
    length = length.zfill(7)
    return length + body


def read_server_answer(sock, attempts=3, wait_s=1.0):
    """Аналог ConnectionManager.readData(): читает первую строку до '\\r\\n',
    пробуя несколько раз (до attempts) с паузой wait_s.
    Возвращает (первая_строка_или_None, полный_байтовый_ответ)."""
    full = b""
    for _ in range(attempts):
        try:
            import select
            ready, _, _ = select.select([sock], [], [], wait_s)
            if not ready:
                continue
            data = sock.recv(1024)
        except (socket.timeout, BlockingIOError):
            continue
        if not data:
            break
        full += data
        # как в приложении: обрезаем всё после \r\n
        if b"\r\n" in full:
            break
    text = full.decode("utf-8", errors="replace")
    first_line = text.split("\r\n", 1)[0] if "\r\n" in text else text
    return (first_line or None, full)


def is_send_successful(serv_answer):
    """Точная копия SendHelper.isSendSuccessful() из приложения.
    Возвращает (bool успешно, описание_причины)."""
    if not serv_answer:
        return False, "no/empty response"
    answer = serv_answer.strip()
    if "0" in answer or "1" in answer:
        return True, "response contains '0' or '1' (accepted)"
    if "2" in answer:
        return False, "response contains '2' (SMS) -> not successful"
    if "3" in answer:
        return True, "response contains '3' (INTENT tariff, TARIF_TYPE=1)"
    if "4" in answer:
        return True, "response contains '4' (WIDGET tariff, TARIF_TYPE=2)"
    return False, "no recognised digit (0/1/3/4) found"


def send_tcp(host, port, frame, timeout=10.0, listen_attempts=3):
    """Отправляет фрейм по TCP, читает ответ как приложение и
    интерпретирует его через is_send_successful()."""
    print("----- TCP FRAME -----")
    print(frame[:600])
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
        sock.sendall(frame.encode("utf-8"))

        first_line, raw = read_server_answer(sock, attempts=listen_attempts)
        sock.close()

        print("----- TCP RECV -----")
        print(raw.decode("utf-8", errors="replace") or "(no response)")
        print("First line (as app sees it):", repr(first_line))

        ok, reason = is_send_successful(first_line)
        print("isSendSuccessful -> {}  ({})".format("TRUE" if ok else "FALSE", reason))
        if ok:
            print("=> packet would be REMOVED from livegps cache")
        else:
            print("=> packet would STAY in cache (will be re-sent later)")
    except OSError as e:
        print("Connection error: {}".format(e))


def main():
    ap = argparse.ArgumentParser(description="Эмуляция Real Time GPS Tracker: отправка rtt006-пакета по TCP")
    ap.add_argument("--host", default=MOCK_HOST, help="адрес сервера (mock: {})".format(MOCK_HOST))
    ap.add_argument("--port", type=int, default=MOCK_PORT, help="порт сервера (mock: {})".format(MOCK_PORT))
    ap.add_argument("--imei", default=MOCK_IMEI,
                    help="IMEI устройства (mock: {})".format(MOCK_IMEI))
    ap.add_argument("--lat", type=float, default=MOCK_LAT, help="широта (mock: Москва)")
    ap.add_argument("--lon", type=float, default=MOCK_LON, help="долгота (mock: Москва)")
    ap.add_argument("--speed", type=float, default=None,
                    help="скорость, км/ч (по умолч. случайная из mock-диапазона)")
    ap.add_argument("--course", type=float, default=None,
                    help="курс, градусы (по умолч. случайный из mock-диапазона)")
    ap.add_argument("--alt", type=float, default=MOCK_ALT, help="высота, м (mock: {})".format(MOCK_ALT))
    ap.add_argument("--listen-attempts", type=int, default=3,
                    help="сколько раз ждать ответ сервера (как readData, по 1 c)")
    args = ap.parse_args()

    speed = args.speed if args.speed is not None else round(random.uniform(MOCK_SPEED_MIN, MOCK_SPEED_MAX), 1)
    course = args.course if args.course is not None else round(random.uniform(MOCK_BEARING_MIN, MOCK_BEARING_MAX), 1)

    print("IMEI: {}\nlat={} lon={} speed={} km/h course={} deg".format(
        args.imei, args.lat, args.lon, speed, course))

    data = build_data(args.lat, args.lon, speed, course, args.alt, MOCK_PACKET_TYPE)
    frame = build_frame(args.imei, data)
    send_tcp(args.host, args.port, frame, listen_attempts=args.listen_attempts)


if __name__ == "__main__":
    main()