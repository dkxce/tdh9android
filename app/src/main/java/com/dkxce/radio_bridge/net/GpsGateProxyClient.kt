/*
 ==============================================================================
  GpsGateProxyClient: local Franson GPSGate HTTP server + remote forwarders
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.5.0

  English:  Runs a local HTTP server speaking the Franson GPSGate protocol
            (GET /?cmd=$FRCMD,...) on a configurable port (default 12014).
            Each valid $FRCMD position is decoded and forwarded to every
            enabled remote server as rtt006 / MT60 / GL200 over TCP. Only
            valid $FRCMD requests get an HTTP 200; anything else gets a 400.

  Russian:  Локальный HTTP-сервер протокола Franson GPSGate на настраиваемом
            порту (по умолчанию 12014). Каждая валидная позиция $FRCMD
            декодируется и пересылается на каждый включённый сервер как
            rtt006 / MT60 / GL200 по TCP. Только валидные $FRCMD запросы
            получают HTTP 200; остальное — 400.
 ==============================================================================
 */

package com.dkxce.radio_bridge.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Callbacks from the proxy to the app. */
fun interface GpsGateLog { fun log(msg: String) }

/**
 * GPSGate proxy. Listens for Franson GPSGate HTTP GETs on [cfg.listenPort],
 * decodes $FRCMD positions and forwards them to all enabled servers.
 */
class GpsGateProxyClient(
    @Volatile private var cfg: GpsGateProxyConfig,
    private val onLog: GpsGateLog,
) {
    // --------------------------------------------------------------- state
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    // stats
    private val statsLock = Any()
    private var rxPackets = 0L
    private var txPackets = 0L
    private var failedPackets = 0L
    private val packetsLog = java.util.ArrayDeque<String>()

    @Volatile private var onStatsChanged: (() -> Unit)? = null

    private fun bump() { onStatsChanged?.invoke() }

    private fun stats(): GpsGateStats = synchronized(statsLock) {
        GpsGateStats(
            running = running.get(),
            rxPackets = rxPackets,
            txPackets = txPackets,
            failedPackets = failedPackets,
            packetsLog = packetsLog.toList(),
        )
    }

    fun statsSnapshot(): GpsGateStats = stats()

    fun setOnStatsChanged(cb: () -> Unit) { onStatsChanged = cb }
    fun resetStats() {
        synchronized(statsLock) {
            rxPackets = 0; txPackets = 0; failedPackets = 0
            packetsLog.clear()
        }
        bump()
    }
    fun clearLog() {
        synchronized(statsLock) { packetsLog.clear() }
        bump()
    }

    fun updateConfig(c: GpsGateProxyConfig) {
        val prevPort = cfg.listenPort
        cfg = c
        if (prevPort != c.listenPort) {
            stopServer()
            if (running.get()) startServer()
        }
    }

    // --------------------------------------------------------------- lifecycle
    fun start() {
        if (running.getAndSet(true)) return
        startServer()
        onLog.log("[GPSGate] proxy started (HTTP :${cfg.listenPort})")
    }

    fun stop() {
        running.set(false)
        stopServer()
        synchronized(statsLock) { packetsLog.clear() }
        onLog.log("[GPSGate] proxy stopped")
    }

    private fun startServer() {
        try {
            val ss = ServerSocket(cfg.listenPort)
            ss.reuseAddress = true
            serverSocket = ss
            executor.execute(::acceptLoop)
        } catch (e: Exception) {
            onLog.log("[GPSGate!] listen failed on :${cfg.listenPort}: ${e.message}")
        }
    }

    private fun stopServer() {
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        for (s in clients) { try { s.close() } catch (_: Exception) { } }
        clients.clear()
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running.get()) {
            try {
                val s = ss.accept()
                clients.add(s)
                onLog.log("[GPSGate] connection: ${s.inetAddress?.hostAddress ?: "?"}")
                executor.execute { handleClient(s) }
            } catch (e: Exception) {
                if (running.get()) onLog.log("[GPSGate!] accept: ${e.message}")
            }
        }
    }

    private fun handleClient(s: Socket) {
        try {
            s.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val out = s.getOutputStream()
            // read the first line only; decide the protocol from it
            val first = reader.readLine() ?: return
            if (first.startsWith("GET ") || first.startsWith("POST ")) {
                handleHttp(reader, out, first)
            } else if (isOkMapLine(first)) {
                handleOkMapConnection(reader, out, first)
            } else {
                // unsupported protocol: drop the connection
                onLog.log("[GPSGate] unsupported protocol, closing: ${first.take(60)}")
                try { s.close() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(s)
            try { s.close() } catch (_: Exception) { }
        }
    }

    /** True when the line looks like an OkMap packet (base / $OKMAP-*). */
    private fun isOkMapLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        return t.startsWith("\$OKMAP") || (!t.startsWith("GET ") && !t.startsWith("POST ") &&
            t.contains(';') && !t.contains(' '))
    }

    /** Handle an HTTP request (GET ?cmd= or POST MapMyTracks). */
    private fun handleHttp(reader: BufferedReader, out: OutputStream, requestLine: String) {
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val ci = line.indexOf(':')
            if (ci > 0) headers[line.substring(0, ci).trim().lowercase()] =
                line.substring(ci + 1).trim()
        }
        if (requestLine.startsWith("GET ")) {
            val target = extractTarget(requestLine)
            val pos = parseTarget(target)
            if (pos != null) {
                respond(out, "200 OK", "OK")
                synchronized(statsLock) {
                    rxPackets++
                    val stamp = java.time.LocalTime.now().withNano(0).toString()
                    packetsLog.addFirst("$stamp $target")
                    while (packetsLog.size > 20) packetsLog.removeLast()
                }
                forward(pos, target)
            } else {
                respond(out, "400 Bad Request", "BAD")
                onLog.log("[GPSGate] invalid request: $target")
            }
            bump()
        } else { // POST
            val clen = headers["content-length"]?.toIntOrNull() ?: 0
            val body = StringBuilder()
            if (clen > 0) {
                val buf = CharArray(minOf(clen, 4096))
                var got = 0
                while (got < clen) {
                    val n = reader.read(buf, 0, minOf(buf.size, clen - got))
                    if (n <= 0) break
                    body.append(buf, 0, n)
                    got += n
                }
            }
            handlePost(requestLine, body.toString(), out)
        }
    }

    /**
     * Handle an OkMap TCP connection: read lines until EOF and forward every
     * recognised track point as a separate outbound packet.
     */
    private fun handleOkMapConnection(reader: BufferedReader, out: OutputStream, firstLine: String) {
        onLog.log("[GPSGate] OkMap connection")
        var line: String? = firstLine
        while (line != null) {
            val t = line.trim()
            if (t.isNotEmpty()) {
                val tr = GpsGateProtocol.parseOkMap(t)
                if (tr != null) {
                    synchronized(statsLock) {
                        rxPackets++
                        val stamp = java.time.LocalTime.now().withNano(0).toString()
                        packetsLog.addFirst("$stamp OKMAP ${tr.sender}")
                        while (packetsLog.size > 20) packetsLog.removeLast()
                    }
                    forwardOkMap(tr, "OKMAP " + tr.sender)
                } else {
                    onLog.log("[GPSGate] unrecognized OkMap line: ${t.take(80)}")
                }
            }
            line = reader.readLine()
        }
    }

    private fun extractTarget(requestLine: String): String {
        // "GET /?cmd=... HTTP/1.1" -> "/?cmd=..."
        val parts = requestLine.split(" ")
        val p = if (parts.size > 1) parts[1] else "/"
        return if (p.startsWith("/")) p else "/$p"
    }

    private fun parseTarget(target: String): FrcmdPosition? {
        // /?cmd=$FRCMD,...
        val qIdx = target.indexOf('?')
        if (qIdx < 0) return null
        val query = target.substring(qIdx + 1)
        val cmdIdx = query.indexOf("cmd=")
        if (cmdIdx < 0) return null
        var cmd = query.substring(cmdIdx + 4)
        val amp = cmd.indexOf('&')
        if (amp >= 0) cmd = cmd.substring(0, amp)
        return GpsGateProtocol.parseFrcmd(java.net.URLDecoder.decode(cmd, Charsets.UTF_8.name()))
    }

    /** Process a MapMyTracks POST: extract points and forward each as a packet. */
    private fun handlePost(rl: String, body: String, out: OutputStream) {
        val req = GpsGateProtocol.parseMmPost(body)
        val path = rl.split(" ").getOrNull(1) ?: "/"
        // always log request + point count (also for stop/get_time with 0 points)
        synchronized(statsLock) {
            val stamp = java.time.LocalTime.now().withNano(0).toString()
            packetsLog.addFirst("$stamp POST ${req.command} points=${req.points.size}")
            while (packetsLog.size > 20) packetsLog.removeLast()
        }
        onLog.log("[GPSGate] POST ${req.command} points=${req.points.size}")
        if (req.command == "start_activity" || req.command == "update_activity") {
            if (req.points.isNotEmpty()) {
                // forward every point, earliest first (they arrive in order)
                val target = "POST $path body"
                synchronized(statsLock) {
                    rxPackets += req.points.size
                }
                for (pt in req.points) {
                    forwardMm(pt, target)
                }
            }
            // XML reply as OruxMaps expects
            val bodyXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><message>" +
                "<type>activity_started</type><activity_id>1</activity_id></message>"
            respondXml(out, "200 OK", bodyXml)
        } else if (req.command == "stop_activity") {
            respondXml(out, "200 OK",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><message><type>activity_stopped</type></message>")
        } else if (req.command == "get_time") {
            val now = System.currentTimeMillis() / 1000
            respondXml(out, "200 OK",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><message><type>time</type><server_time>$now</server_time></message>")
        } else {
            respondXml(out, "200 OK",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><message><type>error</type>" +
                "<reason>request not supported</reason></message>")
        }
    }

    /** Forward a single MapMyTracks point as one outbound packet (unix time as-is). */
    private fun forwardMm(pt: MmPoint, target: String) {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = pt.unix * 1000L
        for (server in cfg.servers) {
            if (!server.enabled) continue
            val p = FrcmdPosition(
                senderId = "", lat = pt.lat, lon = pt.lon,
                speedKmh = 0.0, course = 0.0, altitude = pt.altitude,
            )
            executor.execute { sendMmToServer(server, p, cal, target) }
        }
    }

    /**
     * Forward a single OkMap track point as one outbound packet. The OkMap
     * sender (uuid) is used as the IMEI source (CRC64 when configured), and the
     * speed/bearing/altitude come from the packet as-is.
     */
    private fun forwardOkMap(tr: OkMapTrack, target: String) {
        // OkMap datetime format: "MM/dd/yyyy HH:mm:ss" (UTC)
        val cal = parseOkMapDate(tr.dateTime)
        for (server in cfg.servers) {
            if (!server.enabled) continue
            val p = FrcmdPosition(
                senderId = tr.sender,
                lat = tr.lat,
                lon = tr.lon,
                speedKmh = tr.speedKmh,
                course = tr.bearing,
                altitude = tr.altitude,
            )
            executor.execute { sendMmToServer(server, p, cal, target) }
        }
    }

    /** Parse OkMap "MM/dd/yyyy HH:mm:ss" (UTC) into a Calendar; fallback: now UTC. */
    private fun parseOkMapDate(dateTime: String): java.util.Calendar {
        return try {
            val fmt = java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = fmt.parse(dateTime).time
            }
        } catch (_: Exception) {
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        }
    }

    private fun sendMmToServer(server: GpsGateServer, pos: FrcmdPosition, cal: java.util.Calendar, target: String) {
        try {
            val imei = GpsGateProtocol.resolveImei(server, pos.senderId)
            val payload = buildPacketWithCalendar(server.proto, imei, pos, cal)
            val payloadText = payload.toString(Charsets.UTF_8).trim()
            val ok = try {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(server.host, server.port), 10000)
                    s.soTimeout = 5000
                    s.getOutputStream().write(payload)
                    s.getOutputStream().flush()
                    try { s.getInputStream().read(ByteArray(64)) } catch (_: Exception) { }
                    true
                } finally {
                    try { s.close() } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                false
            }
            if (ok) {
                synchronized(statsLock) { txPackets++ }
                onLog.log("[GPSGate] -> ${server.host}:${server.port} [${server.proto.name}] OK (imei=$imei)")
                onLog.log("[GPSGate]    ${payloadText}")
            } else {
                synchronized(statsLock) { failedPackets++ }
                onLog.log("[GPSGate!] -> ${server.host}:${server.port} [${server.proto.name}] failed (imei=$imei)")
                onLog.log("[GPSGate]    ${payloadText}")
            }
        } catch (e: Exception) {
            synchronized(statsLock) { failedPackets++ }
            onLog.log("[GPSGate!] -> ${server.host}:${server.port} ERROR: ${e.message}")
        }
        bump()
    }

    /** Build an outbound packet using an explicit calendar (POST path). */
    private fun buildPacketWithCalendar(proto: GpsGateProto, imei: String, pos: FrcmdPosition, cal: java.util.Calendar): ByteArray =
        GpsGateProtocol.buildPacketFromCalendar(proto, imei, pos, cal)

    private fun respond(out: OutputStream, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        try {
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) { }
    }

    private fun respondXml(out: OutputStream, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/xml; charset=UTF-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        try {
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) { }
    }

    // ----------------------------------------------------------------- forward
    private fun forward(pos: FrcmdPosition, target: String) {
        for (server in cfg.servers) {
            if (!server.enabled) continue
            executor.execute { sendToServer(server, pos, target) }
        }
    }

    private fun sendToServer(server: GpsGateServer, pos: FrcmdPosition, target: String) {
        try {
            val imei = GpsGateProtocol.resolveImei(server, pos.senderId)
            val payload = GpsGateProtocol.buildPacket(server.proto, imei, pos, cfg.timestampMode)
            val payloadText = payload.toString(Charsets.UTF_8).trim()
            val ok = try {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(server.host, server.port), 10000)
                    s.soTimeout = 5000
                    s.getOutputStream().write(payload)
                    s.getOutputStream().flush()
                    // read a little (up to some bytes) - some servers reply; ignored
                    try { s.getInputStream().read(ByteArray(64)) } catch (_: Exception) { }
                    true
                } finally {
                    try { s.close() } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                false
            }
            if (ok) {
                synchronized(statsLock) { txPackets++ }
                onLog.log("[GPSGate] -> ${server.host}:${server.port} [${server.proto.name}] OK (imei=$imei)")
                onLog.log("[GPSGate]    ${payloadText}")
            } else {
                synchronized(statsLock) { failedPackets++ }
                onLog.log("[GPSGate!] -> ${server.host}:${server.port} [${server.proto.name}] failed (imei=$imei)")
                onLog.log("[GPSGate]    ${payloadText}")
            }
        } catch (e: Exception) {
            synchronized(statsLock) { failedPackets++ }
            onLog.log("[GPSGate!] -> ${server.host}:${server.port} ERROR: ${e.message}")
        }
        bump()
    }
}