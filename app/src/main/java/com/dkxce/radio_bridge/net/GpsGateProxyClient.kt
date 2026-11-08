/*
 ==============================================================================
  GpsGateProxyClient: local Franson GPSGate HTTP server + remote forwarders
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.3.0

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
            s.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val out = s.getOutputStream()
            var answered = false
            while (running.get() && !answered) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                // HTTP request line: GET /?cmd=... HTTP/1.1
                if (line.startsWith("GET ")) {
                    val target = extractTarget(line)
                    val pos = parseTarget(target)
                    answered = true
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
                }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(s)
            try { s.close() } catch (_: Exception) { }
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