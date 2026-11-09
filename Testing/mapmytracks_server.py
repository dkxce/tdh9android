#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HTTP-сервер приёма сообщений в формате MapMyTracks (live tracking).

Карты/ОкМарты отправляют позиции по HTTP POST (как принимает OruxPalsServer,
метод OnMMT, TCPServer.cs:3409). Формат:

  - авторизация: HTTP Basic (login:password), пароль сверяется как Buddie.Hash(login)
    (программное; можно отключить флагом); при отсутствии — 401.
  - тело POST: form-urlencoded (application/x-www-form-urlencoded)
  - команды (поле "request"):
      start_activity   -> ответ XML "<activity_started><activity_id>N</activity_id></activity_started>"
      update_activity  -> то же (activity_id растёт счётчиком)
      stop_activity    -> ответ "<activity_stopped>"
      get_time         -> ответ "<server_time><unix></server_time>"
  - поле "points": список точек, разделённых пробелом, группами по 4 значения:
        <lat> <lon> <alt> <unix_time>  [повторяется N раз]
    Сервер берёт ПОСЛЕДНЮЮ точку как текущую позицию.

Сервер выводит в консоль: raw-запрос (метод, путь, заголовки, тело) и разобранную
позицию/команду. Запуск:  python mapmytracks_server.py [--host 0.0.0.0] [--port 12014]
"""

"""
Реальный лог OruxMaps:
    
[+] connection from ('127.0.0.1', 53612)
----- RAW REQUEST -----
POST /oruxpals/m/ HTTP/1.1
content-type: application/x-www-form-urlencoded
content-length: 272
host: 192.168.177.99:12014
connection: Keep-Alive
accept-encoding: gzip
user-agent: okhttp/3.12.0

request=start_activity&activity=running&privacy=private&source=OruxMaps&title=2026-11-08%2019%3A47%3A3320261109_0816&version=8050502&points=44.08439333333334%2040.02318833333333%20305.56662%201794201381%2044.084201666666665%2040.023241666666665%20302.98477%201794201390%20
-----------------------
[MapMyTracks] user='(anonymous)' request='start_activity' cache-points=2
[MapMyTracks] position lat=44.084201666666665 lon=40.023241666666665 alt=302.98477 time=2026-11-09 05:16:30
    point: lat=44.08439333333334 lon=40.02318833333333 alt=305.56662 unix=1794201381 2026-11-09 05:16:21
    point: lat=44.084201666666665 lon=40.023241666666665 alt=302.98477 unix=1794201390 2026-11-09 05:16:30
[-] ('127.0.0.1', 53612) done
[+] connection from ('127.0.0.1', 53632)
----- RAW REQUEST -----
POST /oruxpals/m/ HTTP/1.1
content-type: application/x-www-form-urlencoded
content-length: 110
host: 192.168.177.99:12014
connection: Keep-Alive
accept-encoding: gzip
user-agent: okhttp/3.12.0

request=update_activity&activity_id=1&points=44.08437666666667%2040.02316833333333%20301.72443%201794201480%20
-----------------------
[MapMyTracks] user='(anonymous)' request='update_activity' cache-points=1
[MapMyTracks] position lat=44.08437666666667 lon=40.02316833333333 alt=301.72443 time=2026-11-09 05:18:00
    point: lat=44.08437666666667 lon=40.02316833333333 alt=301.72443 unix=1794201480 2026-11-09 05:18:00
[-] ('127.0.0.1', 53632) done
[+] connection from ('127.0.0.1', 53634)
----- RAW REQUEST -----
POST /oruxpals/m/ HTTP/1.1
content-type: application/x-www-form-urlencoded
content-length: 35
host: 192.168.177.99:12014
connection: Keep-Alive
accept-encoding: gzip
user-agent: okhttp/3.12.0

request=stop_activity&activity_id=2
-----------------------
[MapMyTracks] user='(anonymous)' request='stop_activity' cache-points=0
[-] ('127.0.0.1', 53634) done

"""

import argparse
import base64
import socketserver
import urllib.parse
import time
import xml.sax.saxutils


HTTP_HEADER_END = b"\r\n\r\n"


def buddie_hash(name: str) -> int:
    """Числовой 'пароль' для имени (аналог Buddie.Hash в OruxPalsServer Buddies.cs)."""
    up = name.split("-", 1)[0]
    up = (up + " " * 9)[:9]
    h = 0x2017
    i = 0
    while i < 9:
        h ^= ord(up[i]) << 16
        h ^= ord(up[i + 1]) << 8
        h ^= ord(up[i + 2])
        i += 3
    return h & 0x7FFFFF


def parse_http(data: bytes):
    """Разбирает сырой HTTP-запрос. Возвращает dict(head, path, method, body) или None."""
    sep = data.find(HTTP_HEADER_END)
    if sep < 0:
        return None
    head = data[:sep].decode("utf-8", errors="replace")
    body = data[sep + 4:]
    lines = head.split("\r\n")
    if not lines:
        return None
    request_line = lines[0]
    parts = request_line.split(" ")
    if len(parts) < 2:
        return None
    method, path = parts[0], parts[1]
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return {"method": method, "path": path, "headers": headers, "body": body}


def parse_basic_auth(header):
    """'Basic <base64>' -> (user, password) или (None, None)."""
    if not header or not header.lower().startswith("basic "):
        return None, None
    try:
        decoded = base64.b64decode(header.split(" ", 1)[1]).decode("utf-8", errors="replace")
    except Exception:
        return None, None
    if ":" in decoded:
        u, p = decoded.split(":", 1)
        return u, p
    return decoded, ""


def split_points(points_str):
    """Разбирает 'points': лат лон альт юникс (группы по 4). Вернёт список точек dict."""
    if not points_str:
        return []
    tokens = points_str.split()
    result = []
    for i in range(0, len(tokens) - 3, 4):
        try:
            lat = float(tokens[i])
            lon = float(tokens[i + 1])
            alt = float(tokens[i + 2])
            ts = int(float(tokens[i + 3]))
        except (ValueError, IndexError):
            continue
        result.append({"lat": lat, "lon": lon, "alt": alt, "unix": ts,
                       "time": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(ts))})
    return result


def xml_message(inner):
    return '<?xml version="1.0" encoding="UTF-8"?><message>%s</message>' % inner


class Handler(socketserver.BaseRequestHandler):
    activity_counter = 0

    def handle(self):
        data = b""
        try:
            # 1) читаем заголовки до \r\n\r\n
            while HTTP_HEADER_END not in data:
                chunk = self.request.recv(4096)
                if not chunk:
                    return
                data += chunk

            # 2) определяем Content-Length
            head_part = data.split(HTTP_HEADER_END, 1)[0].decode("utf-8", errors="replace")
            clen = 0
            for ln in head_part.split("\r\n"):
                if ln.lower().startswith("content-length:"):
                    try:
                        clen = int(ln.split(":", 1)[1].strip())
                    except ValueError:
                        clen = 0
                    break

            # 3) дочитываем ровно тело
            body_start = data.find(HTTP_HEADER_END) + 4
            have_body = len(data) - body_start
            while have_body < clen:
                chunk = self.request.recv(min(4096, clen - have_body))
                if not chunk:
                    break
                data += chunk
                have_body = len(data) - body_start
        except Exception:
            pass

        addr = self.client_address
        print("[+] connection from {}".format(addr), flush=True)

        req = parse_http(data)
        if req is None:
            self._send(400, "text/plain", "Bad Request")
            return

        # raw-вывод
        print("----- RAW REQUEST -----", flush=True)
        print("{} {} HTTP/1.1".format(req["method"], req["path"]), flush=True)
        for k, v in req["headers"].items():
            print("{}: {}".format(k, v), flush=True)
        print("", flush=True)
        body_text = req["body"].decode("utf-8", errors="replace")
        print(body_text or "(empty body)", flush=True)
        print("-----------------------", flush=True)

        if req["method"].upper() != "POST":
            self._send(405, "text/plain", "Method Not Allowed")
            return

        # авторизация
        user, password = parse_basic_auth(req["headers"].get("authorization", ""))
        if self.server.require_auth:
            if user is None or buddie_hash(user.upper()) != int(password or -1):
                self._send_auth_required()
                return

        # парсим тело
        params = urllib.parse.parse_qs(body_text)
        request = (params.get("request") or [""])[0]
        points = (params.get("points") or [""])[0]

        print("[MapMyTracks] user={!r} request={!r} cache-points={}".format(
            user or "(anonymous)", request, len(split_points(points))), flush=True)

        if request in ("start_activity", "update_activity"):
            Handler.activity_counter += 1
            pts = split_points(points)
            if pts:
                last = pts[-1]
                print("[MapMyTracks] position lat={} lon={} alt={} time={}".format(
                    last["lat"], last["lon"], last["alt"], last["time"]), flush=True)
                for p in pts:
                    print("    point: lat={} lon={} alt={} unix={} {}".format(
                        p["lat"], p["lon"], p["alt"], p["unix"], p["time"]), flush=True)
            resp = xml_message(
                "<type>activity_started</type><activity_id>{}</activity_id>".format(
                    Handler.activity_counter))
            self._send(200, "text/xml; charset=UTF-8", resp)
        elif request == "stop_activity":
            self._send(200, "text/xml; charset=UTF-8", xml_message("<type>activity_stopped</type>"))
        elif request == "get_time":
            self._send(200, "text/xml; charset=UTF-8",
                       xml_message("<type>time</type><server_time>{}</server_time>".format(int(time.time()))))
        else:
            self._send(200, "text/xml; charset=UTF-8",
                       xml_message("<type>error</type><reason>request not supported</reason>"))
        print("[-] {} done".format(addr), flush=True)

    def _send(self, code, ctype, text):
        reason = {200: "OK", 400: "Bad Request", 401: "Unauthorized",
                  405: "Method Not Allowed"}.get(code, "Error")
        head = ("HTTP/1.1 {} {}\r\n"
                "Content-Type: {}\r\n"
                "Content-Length: {}\r\n"
                "Connection: close\r\n\r\n").format(code, reason, ctype, len(text))
        try:
            self.request.sendall(head.encode() + text.encode())
        except Exception:
            pass

    def _send_auth_required(self):
        head = ("HTTP/1.1 401 Unauthorized\r\n"
                "WWW-Authenticate: Basic realm=\"MapMyTracks\"\r\n"
                "Connection: close\r\n\r\n")
        try:
            self.request.sendall(head.encode())
        except Exception:
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True

    def __init__(self, addr, handler, require_auth=True):
        self.require_auth = require_auth
        super().__init__(addr, handler)


def main():
    ap = argparse.ArgumentParser(description="MapMyTracks HTTP-сервер (приём POST-треков)")
    ap.add_argument("--host", default="0.0.0.0", help="адрес прослушивания (default 0.0.0.0)")
    ap.add_argument("--port", type=int, default=12014, help="порт (default 12014)")
    ap.add_argument("--no-auth", action="store_true", default="OFF", help="отключить проверку Basic Auth")
    args = ap.parse_args()

    srv = Server((args.host, args.port), Handler, require_auth=not args.no_auth)
    print("MapMyTracks server listening on {}:{}".format(args.host, args.port))
    print("Auth: {}".format("ON (Basic, password=Buddie.Hash(login))" if args.no_auth is False else "OFF"))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()