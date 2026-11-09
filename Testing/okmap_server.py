#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Сервер приёма сообщений в формате OkMap Live Tracking (по TCP).

Спецификация (официальный форум OkMap, https://www.okmap.org/forum/topic.asp?TOPIC_ID=1167):
  - поля разделены точкой с запятой ';'
  - принимаются ТРИ версии протокола (обратно совместимы):

  [1] Базовая (старая):
      <id>;<lon>;<lat>;<alt>;<MM/dd/yyyy hh:mm:ss>;<speed>;<bearing>[;<thickness>;<color>];
  [2] $OKMAP-V01:
      $OKMAP-V01;<id>;<lon>;<lat>;<alt>;<datetime>;<speed>;<bearing>;<newseg>[;<thickness>;<color>];
  [3] $OKMAP-V02 (см. 13.8.0):
      $OKMAP-V02;T;<id>;<lon>;<lat>;<alt>;<datetime>;<speed>;<bearing>;<newseg>[;<thickness>;<color>];   (track point)
      $OKMAP-V02;W;<id>;<name>;<comment>;<description>;<source>;<link>;<symbol>;<type>;<lon>;<lat>;<alt>;<datetime>;  (waypoint)

Сервер слушает TCP, принимает строки до символа новой строки, разбирает и
выводит результат в консоль. Соединений — несколько, каждое в своём потоке.

Запуск:  python okmap_server.py [--host 0.0.0.0] [--port 10001]
"""

"""
Реальный лог OruxMaps:
    
[+] connection from ('127.0.0.1', 55170)
[raw] $OKMAP-V01;031fd58f-2dba-41c6-97fe-02c29f1fc730;40.023345;44.08429833333333;299.55234;11/09/2026 05:07:50;0;0;0;3;-65536;
[OKMAP $OKMAP-V01 track] sender='031fd58f-2dba-41c6-97fe-02c29f1fc730' lat=44.08429833333333 lon=40.023345 alt=299.55234 speed=0.0 km/h bearing=0.0 time=2026-11-09 05:07:50 newseg=0 thickness=3 color=-65536
[-] ('127.0.0.1', 55170) disconnected

[+] connection from ('127.0.0.1', 55174)
[raw] $OKMAP-V01;031fd58f-2dba-41c6-97fe-02c29f1fc730;40.02338333333333;44.08410333333333;299.90524;11/09/2026 05:08:46;0;0;0;3;-65536;
[OKMAP $OKMAP-V01 track] sender='031fd58f-2dba-41c6-97fe-02c29f1fc730' lat=44.08410333333333 lon=40.02338333333333 alt=299.90524 speed=0.0 km/h bearing=0.0 time=2026-11-09 05:08:46 newseg=0 thickness=3 color=-65536
[-] ('127.0.0.1', 55174) disconnected

"""

import argparse
import socket
import threading
import datetime


# Поля для datetime: MM/dd/yyyy hh:mm:ss (универсальное время)
DATETIME_FMT = "%m/%d/%Y %H:%M:%S"


def parse_datetime(raw):
    """Парсит дату-время OkMap. Вернёт строку или None."""
    try:
        dt = datetime.datetime.strptime(raw.strip(), DATETIME_FMT)
        return dt.strftime("%Y-%m-%d %H:%M:%S")
    except (ValueError, TypeError):
        return None


def parse_line(line):
    """Разбирает одну строку OkMap. Возвращает dict с результатом или None."""
    line = line.rstrip("\r\n")
    if not line:
        return None
    parts = [p.strip() for p in line.split(";")]

    # ---- $OKMAP-V02 ----
    if parts and parts[0] == "$OKMAP-V02":
        if len(parts) < 4:
            return None
        rtype = parts[1].upper()
        sender = parts[2]
        if rtype == "T" and len(parts) >= 9:   # track point
            lon, lat = _f(parts[3]), _f(parts[4])
            alt = _f(parts[5])
            ts = parse_datetime(parts[6])
            speed = _f(parts[7])
            bearing = _f(parts[8])
            newseg = parts[9] if len(parts) > 9 else None
            thickness = parts[10] if len(parts) > 10 else None
            color = parts[11] if len(parts) > 11 else None
            return {
                "version": "$OKMAP-V02", "type": "track",
                "sender": sender, "longitude": lon, "latitude": lat,
                "altitude": alt, "datetime": ts, "speed": speed,
                "bearing": bearing, "new_segment": newseg,
                "thickness": thickness, "color": color,
            }
        if rtype == "W" and len(parts) >= 14:  # waypoint
            return {
                "version": "$OKMAP-V02", "type": "waypoint",
                "sender": sender, "name": parts[3], "comment": parts[4],
                "description": parts[5], "source": parts[6], "link": parts[7],
                "symbol": parts[8], "wpt_type": parts[9],
                "longitude": _f(parts[10]), "latitude": _f(parts[11]),
                "altitude": _f(parts[12]), "datetime": parse_datetime(parts[13]),
            }
        return None

    # ---- $OKMAP-V01: $OKMAP-V01;<id>;<lon>;<lat>;<alt>;<dt>;<speed>;<bearing>;<newseg>[;...]
    if parts and parts[0] == "$OKMAP-V01" and len(parts) >= 9:
        return {
            "version": "$OKMAP-V01", "type": "track",
            "sender": parts[1],
            "longitude": _f(parts[2]), "latitude": _f(parts[3]),
            "altitude": _f(parts[4]), "datetime": parse_datetime(parts[5]),
            "speed": _f(parts[6]), "bearing": _f(parts[7]),
            "new_segment": parts[8],
            "thickness": parts[9] if len(parts) > 9 else None,
            "color": parts[10] if len(parts) > 10 else None,
        }

    # ---- Базовая: <id>;<lon>;<lat>;<alt>;<dt>;<speed>;<bearing>[;...]
    if len(parts) >= 7:
        return {
            "version": "base", "type": "track",
            "sender": parts[0],
            "longitude": _f(parts[1]), "latitude": _f(parts[2]),
            "altitude": _f(parts[3]), "datetime": parse_datetime(parts[4]),
            "speed": _f(parts[5]), "bearing": _f(parts[6]),
            "new_segment": None,
            "thickness": parts[7] if len(parts) > 7 else None,
            "color": parts[8] if len(parts) > 8 else None,
        }
    return None


def _f(s):
    """Число или None."""
    try:
        return float(s)
    except (ValueError, TypeError):
        return None


def format_for_print(d):
    """Красивый вывод в консоль."""
    if d["type"] == "track":
        return (
            "[OKMAP {} track] sender={!r} lat={} lon={} alt={} speed={} km/h "
            "bearing={} time={} newseg={} thickness={} color={}".format(
                d["version"], d["sender"], d["latitude"], d["longitude"],
                d["altitude"], d["speed"], d["bearing"], d["datetime"],
                d["new_segment"], d["thickness"], d["color"]))
    return (
        "[OKMAP {} waypoint] sender={!r} name={!r} comment={!r} desc={!r} "
        "lat={} lon={} alt={} time={}".format(
            d["version"], d["sender"], d["name"], d["comment"],
            d["description"], d["latitude"], d["longitude"],
            d["altitude"], d["datetime"]))


def handle_client(conn, addr):
    print("[+] connection from {}".format(addr))
    buf = b""
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                break
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                text = line.decode("utf-8", errors="replace").strip()
                if not text:
                    continue
                print("[raw] {}".format(text), flush=True)
                parsed = parse_line(text)
                if parsed:
                    print(format_for_print(parsed), flush=True)
                else:
                    print("[!] unrecognized OkMap line: {!r}".format(text), flush=True)
    except ConnectionResetError:
        pass
    finally:
        conn.close()
        print("[-] {} disconnected".format(addr))


def main():
    ap = argparse.ArgumentParser(description="OkMap Live Tracking TCP-сервер (приём + вывод в консоль)")
    ap.add_argument("--host", default="0.0.0.0", help="адрес прослушивания (default 0.0.0.0)")
    ap.add_argument("--port", type=int, default=10001, help="порт (default 10001)")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(16)
    print("OkMap server listening on {}:{}".format(args.host, args.port))
    print("Versions: base, $OKMAP-V01, $OKMAP-V02 (track+waypoint)")
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        srv.close()


if __name__ == "__main__":
    main()