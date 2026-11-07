/*
 ==============================================================================
  APRS-IS proxy: local APRS-IS clients <-> remote APRS-IS server <-> radio
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.0.0

  English:  A proxy that fronts an inbound (local) APRS-IS TCP server and a
            single outbound (internet) APRS-IS connection. It relays traffic
            between local APRS-IS clients and the internet server, and (when
            asked) between the BLE radio and the internet / local clients.

  Data flows (all APRS-IS text lines):
      internet  -> all local clients   (as-is)
      internet  -> radio               (as-is; encoded via Ax25 on the radio side)
      client    -> internet            (as-is)
      client    -> radio               (as-is)
      client    -> client              (NOT relayed)
      radio     -> internet            (IGate-corrected: added ,qAR,<IGATE_NAME>)
      radio     -> local clients       (IGate-corrected: added ,qAR,<IGATE_NAME>)

  Upstream credentials source (config.source):
      OWN        - always use the proxy's own callsign/pass/filter
      FIRST/LAST - use the first/last connected local client's login; when no
                   local client is connected, fall back to the proxy's own.
      If the effective login (callsign/pass/filter/server) changes, the proxy
      reconnects the upstream connection.

  Keepalive / retry / cache:
      ping every configurable interval (default 90s) as an APRS-IS comment "#".
      On network failure the connection is retried; packets that need to go to
      the internet are buffered up to config.cacheSeconds (default 90s) and
      dropped when they expire.
      The upstream connection idles out after config.idleTimeoutSeconds when
      there is no requirement (no local clients and no radio<->internet mode).

  Russian:  Прокси между локальными APRS-IS клиентами и интернет-сервером
            APRS-IS, с опциональным мостом в рацию (BLE). Пробрасывает трафик
            по направлениям, как указано выше; клиент-клиент не пробрасывается.
 ==============================================================================
 */

package com.dkxce.radio_bridge.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Where the upstream login credentials come from. */
enum class ProxySource { OWN, FIRST_CLIENT, LAST_CLIENT }

/** Readable multi-line config for the APRS-IS proxy. */
data class ProxyConfig(
    val enabled: Boolean = false,              // master switch (UI checkbox)
    // ----- own APRS-IS credentials (upstream; used when OWN or no client) -----
    val callsign: String = "NOCALL-10",
    val passcode: Int = 0,                     // 0 => computed from callsign
    val server: String = "rotate.aprs2.net",
    val port: Int = 14580,
    val filter: String = "",
    val igateName: String = "NOCALL-1",        // used for ,qAR,<IGATE_NAME> correction
    val source: ProxySource = ProxySource.LAST_CLIENT,
    // ----- forwarding switches -----
    val radioToClients: Boolean = true,        // radio -> local clients   (IGate qAR)
    val radioToInternet: Boolean = true,       // radio -> internet        (IGate qAR)
    val internetToRadio: Boolean = true,       // internet -> radio        (as-is)
    val clientsToRadio: Boolean = true,        // local client -> radio    (as-is)
    val applyQar: Boolean = true,              // add ,qAR,<IGATE_NAME> on radio->out
    // ----- AIS (not implemented yet; stored for future use) -----
    val aisEnabled: Boolean = false,           // AIS feature switch (UI only)
    val aisPort: Int = 0,                      // AIS UDP/TCP port (UI only)
    // ----- timing -----
    val pingSeconds: Long = 90,                // upstream keepalive comment interval
    val cacheSeconds: Long = 90,               // buffer outbound-internet on disconnect
    val idleTimeoutSeconds: Long = 300,        // 5 min no-requirement upstream idle cut
)

/** Callbacks bridging the proxy to the rest of the app. */
fun interface ProxyRxToRadio {         // send an APRS text line to the radio
    fun onText(text: String)
}
fun interface ProxyLog {
    fun log(msg: String)
}

/** Current proxy stats, pushed to the UI through [AprsIsProxyClient.onStats]. */
data class ProxyStats(
    val rxRadio: Long = 0,        // packets from radio (inbound from radio)
    val txRadio: Long = 0,        // packets to radio
    val rxInternet: Long = 0,     // packets from internet server
    val txInternet: Long = 0,     // packets to internet server
    val rxClients: Long = 0,      // packets from local clients
    val txClients: Long = 0,      // packets to local clients
    val clientList: List<String> = emptyList(),
    val packetsLog: List<String> = emptyList(),   // last N packets (reversed: newest first)
    val running: Boolean = false,
    val upstreamConnected: Boolean = false,
    val effectiveCallsign: String = "",
)

/**
 * APRS-IS proxy. Runs a local APRS-IS server (accept loop in a daemon thread)
 * and an outbound upstream connection (its own thread). Never runs socket
 * work on the caller's thread.
 */
class AprsIsProxyClient(
    @Volatile private var cfg: ProxyConfig,
    private val onTxToRadio: ProxyRxToRadio,
    private val onLog: ProxyLog,
) {
    // ------------------------------------------------------------------ state
    @Volatile private var running = false
    private val lock = Any()
    private val clients = CopyOnWriteArrayList<LocalClient>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    // upstream
    @Volatile private var upstreamSock: Socket? = null
    @Volatile private var upstreamConnected = false
    @Volatile private var effectiveCallsign = ""
    private var upstreamThread: Thread? = null
    private var idleThread: Thread? = null

    // outbound cache (packets wanting to reach internet while disconnected)
    private data class Cached(val payload: String, val expiry: Long)
    private val cache = ConcurrentHashMap.newKeySet<Cached>()
    private val cacheLock = Any()

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true }
    }

    // ------------------------------------------------------------------ stats
    // Russian: счётчики пакетов по направлениям и лог последних пакетов.
    @Volatile private var onStatsChanged: (() -> Unit)? = null
    private val statsLock = Any()
    private var rxRadio = 0L
    private var txRadio = 0L
    private var rxInternet = 0L
    private var txInternet = 0L
    private var rxClients = 0L
    private var txClients = 0L
    private val packetsLog = java.util.ArrayDeque<String>()

    /** Install a callback fired whenever stats change (UI refresh). */
    fun setOnStatsChanged(cb: () -> Unit) { onStatsChanged = cb }

    /** Zero all counters and clear the packet log. */
    fun resetStats() {
        synchronized(statsLock) {
            rxRadio = 0; txRadio = 0; rxInternet = 0
            txInternet = 0; rxClients = 0; txClients = 0
            packetsLog.clear()
        }
        onStatsChanged?.invoke()
    }

    private fun pushPacket(direction: String, text: String) {
        // Russian: добавляем метку направления и строку пакета в лог (обрезка).
        val line = "[$direction] $text"
        synchronized(statsLock) {
            packetsLog.addFirst(line)
            while (packetsLog.size > 20) packetsLog.removeLast()
        }
    }

    private fun snapshotStats(): ProxyStats {
        synchronized(statsLock) {
            return ProxyStats(
                rxRadio = rxRadio, txRadio = txRadio,
                rxInternet = rxInternet, txInternet = txInternet,
                rxClients = rxClients, txClients = txClients,
                clientList = clientList(),
                packetsLog = packetsLog.toList(),
                running = running,
                upstreamConnected = upstreamConnected,
                effectiveCallsign = effectiveCallsign,
            )
        }
    }

    /** Current stats snapshot (counters, client list, packet log). */
    fun stats(): ProxyStats = snapshotStats()

    private fun bumpStats() { onStatsChanged?.invoke() }

    // ------------------------------------------------------------------ public
    val isConnected: Boolean get() = upstreamConnected

    fun clientList(): List<String> = clients.map { c ->
        "aprs://${c.callsign.ifEmpty { "?" }}@${c.address}:${c.port}" +
            (if (c.verified) "/tx" else "/rx")
    }

    fun start() {
        if (running) return
        running = true
        startLocalServer()
        // upstream is (re)connected by the idle/requirement monitor + whenever
        // it is needed; kick the first connection right away.
        executor.execute { ensureUpstream() }
        idleThread = Thread(::idleLoop).apply { isDaemon = true; start() }
        onLog.log("[Proxy] started (server :$cfg.port)")
    }

    fun stop() {
        running = false
        synchronized(lock) {
            try { serverSocket?.close() } catch (_: Exception) { }
            serverSocket = null
            try { upstreamSock?.close() } catch (_: Exception) { }
            upstreamSock = null
            for (c in clients) {
                try { c.socket.close() } catch (_: Exception) { }
            }
            clients.clear()
        }
        acceptThread?.join(2000); acceptThread = null
        upstreamThread?.join(2000); upstreamThread = null
        idleThread?.join(2000); idleThread = null
        synchronized(cacheLock) { cache.clear() }
        upstreamConnected = false
        onLog.log("[Proxy] stopped")
    }

    fun updateConfig(cfg: ProxyConfig) {
        val prev = this.cfg
        this.cfg = cfg
        // If the effective upstream login (or the port of the local server)
        // changed, restart the pieces that depend on them.
        val loginChanged = effectiveLoginForUpstream() != null &&
            prev.source != cfg.source ||
            prev.callsign != cfg.callsign || prev.passcode != cfg.passcode ||
            prev.server != cfg.server || prev.port != cfg.port ||
            prev.filter != cfg.filter
        if (prev.port != cfg.port) {
            // local listen port changed -> rebuild local server
            synchronized(lock) {
                try { serverSocket?.close() } catch (_: Exception) { }
                serverSocket = null
            }
            if (running) startLocalServer()
        }
        if (running && (loginChanged ||
                prev.radioToInternet != cfg.radioToInternet ||
                prev.internetToRadio != cfg.internetToRadio)) {
            executor.execute { ensureUpstream() }
            onLog.log("[Proxy] config updated, upstream re-evaluated")
        }
    }

    /** A frame arrived from the radio (raw KISS/AX.25 decoded to text by caller). */
    fun radioRx(text: String) {
        if (!running || text.isBlank()) return
        val corrected = if (cfg.applyQar) appendToPath(text, "qAR,${cfg.igateName}") else text
        synchronized(statsLock) { rxRadio++ }
        pushPacket("RX<-radio", corrected)
        // radio -> local clients (IGate-corrected)
        if (cfg.radioToClients) broadcastToClients(corrected)
        // radio -> internet (IGate-corrected); buffered on disconnect
        if (cfg.radioToInternet) sendToInternet(corrected)
        bumpStats()
    }

    // ------------------------------------------------------- local APRS-IS server
    private fun startLocalServer() {
        if (!running) return
        try {
            val ss = ServerSocket(cfg.port)
            ss.reuseAddress = true
            synchronized(lock) { serverSocket = ss }
            acceptThread = Thread(::acceptLoop, "proxy-accept").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            onLog.log("[Proxy!] listen failed on :${cfg.port}: ${e.message}")
        }
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            try {
                val s = ss.accept()
                val cl = LocalClient(s)
                clients.add(cl)
                onLog.log("[Proxy] client connected: ${cl.address}")
                executor.execute { clientLoop(cl) }
            } catch (e: Exception) {
                if (running) onLog.log("[Proxy!] accept: ${e.message}")
            }
        }
    }

    private fun removeClient(c: LocalClient) {
        clients.remove(c)
        try { c.socket.close() } catch (_: Exception) { }
        onLog.log("[Proxy] client disconnected: ${c.address}")
        executor.execute { ensureUpstream() }
    }

    private fun clientLoop(cl: LocalClient) {
        try {
            cl.socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(cl.socket.getInputStream(), Charsets.UTF_8))
            val out = cl.socket.getOutputStream()
            sendLine(out, "# ${cfg.igateName} APRS bridge: connection accepted")
            while (running) {
                val line = reader.readLine() ?: break
                val text = line.trim()
                if (text.isEmpty()) continue
                handleClientLine(cl, out, text)
            }
        } catch (_: Exception) {
        } finally {
            removeClient(cl)
        }
    }

    private fun handleClientLine(cl: LocalClient, out: OutputStream, text: String) {
        val low = text.lowercase()
        if (low.startsWith("user ") || low.startsWith("user\t")) {
            // login (APRS-IS style). We parse but do NOT hard-reject on passcode.
            val (call, passRaw, filterText) = parseLogin(text)
            cl.callsign = call
            cl.passcode = passRaw.toIntOrNull() ?: -1
            cl.filter = filterText
            val expected = computePasscode(call)
            cl.verified = (passRaw.toIntOrNull() ?: -1) == expected
            onLog.log("[Proxy] client login: $call pass=$passRaw verified=${cl.verified}")
            sendLine(out, "# logresp $call ${if (cl.verified) "verified" else "unverified"}, server ${cfg.igateName}")
            // Upstream may need to reconnect if this client is now the "last".
            executor.execute { ensureUpstream() }
            return
        }
        // derive callsign from first packet, like the other servers
        if (cl.callsign.isEmpty() && text.isNotEmpty() && !text.startsWith("#")) {
            val call = text.substringBefore('>').trim()
            if (call.isNotEmpty()) cl.callsign = call
        }
        // a plain APRS data line from a client
        if (text.startsWith("#")) return          // comments never relayed
        synchronized(statsLock) { rxClients++ }
        pushPacket("RX<-client", text)
        // client -> internet (as-is); buffered on disconnect
        sendToInternet(text)
        // client -> radio (as-is)
        if (cfg.clientsToRadio) {
            try { onTxToRadio.onText(text) } catch (_: Exception) { }
            synchronized(statsLock) { txRadio++ }
        }
        bumpStats()
    }

    // ------------------------------------------------------------- upstream
    private fun parseLogin(text: String): Triple<String, String, String> {
        val parts = text.split(" ")
        var call = ""
        var pass = ""
        var filter = ""
        for (i in parts.indices) {
            when (parts[i].lowercase()) {
                "user" -> if (i + 1 < parts.size && call.isEmpty()) call = parts[i + 1]
                "pass" -> if (i + 1 < parts.size) pass = parts[i + 1]
                "filter" -> {
                    // filter is the REST of the line (may contain spaces)
                    if (i + 1 < parts.size) filter = parts.subList(i + 1, parts.size).joinToString(" ")
                    // capture the rest and stop parsing
                    return Triple(call.trim(), pass.trim(), filter.trim())
                }
            }
        }
        return Triple(call.trim(), pass.trim(), filter.trim())
    }

    /** Compute the login line the proxy should currently present to upstream. */
    private fun effectiveLoginForUpstream(): Triple<String, Int, String>? {
        // returns (callsign, passcode, filter) ; null -> no requirement / client
        val localWithLogin = clients.filter { it.callsign.isNotEmpty() }
        val useClient = when (cfg.source) {
            ProxySource.OWN -> null
            ProxySource.FIRST_CLIENT -> localWithLogin.firstOrNull()
            ProxySource.LAST_CLIENT -> localWithLogin.lastOrNull()
        }
        return if (useClient != null) {
            Triple(useClient.callsign, useClient.passcode, actualFilter(useClient.filter))
        } else {
            // fallback: own config
            Triple(cfg.callsign, cfg.passcode, actualFilter(cfg.filter))
        }
    }

    private fun actualFilter(f: String): String {
        val base = f.trim()
        return if (base.isNotEmpty()) base else "d/${cfg.igateName.substringBefore('-')}*"
    }

    private fun requirementActive(): Boolean {
        return running && cfg.enabled &&
            (clients.isNotEmpty() || cfg.radioToInternet || cfg.internetToRadio)
    }

    private fun ensureUpstream() {
        if (!requirementActive()) { closeUpstream(); return }
        val eff = effectiveLoginForUpstream() ?: run { closeUpstream(); return }
        val callsign = eff.first
        val passcode = if (eff.second > 0) eff.second else computePasscode(callsign)
        val filter = eff.third

        // same login already connected -> nothing to do
        synchronized(lock) {
            if (upstreamConnected && effectiveCallsign == callsign) return
        }

        closeUpstream()
        if (!running) return

        val server = cfg.server
        val port = cfg.port
        effectiveCallsign = callsign
        onLog.log("[Proxy] connecting upstream $callsign@$server:$port (filter $filter)")

        upstreamThread = Thread {
            var sock: Socket? = null
            try {
                val s = Socket()
                s.connect(InetSocketAddress(server, port), 15000)
                s.soTimeout = (cfg.pingSeconds.coerceAtLeast(5) * 1000 / 2).toInt()
                s.keepAlive = true
                synchronized(lock) { upstreamSock = s }
                upstreamConnected = true
                val login = "user $callsign pass $passcode vers TIDRadioBridge v1.0 filter $filter\r\n"
                s.getOutputStream().write(login.toByteArray())
                flushCache(s.getOutputStream())
                // read loop with embedded keepalive ping
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                var lastPing = System.currentTimeMillis()
                while (running && requirementActive() &&
                    effectiveCallsign == callsign) {
                    var line: String?
                    try {
                        line = reader.readLine()
                    } catch (_: SocketTimeoutException) {
                        // idle: send keepalive comment ping at interval
                        if (System.currentTimeMillis() - lastPing >= cfg.pingSeconds * 1000) {
                            sendLine(s.getOutputStream(), "# ping (${cfg.igateName})")
                            lastPing = System.currentTimeMillis()
                        }
                        continue
                    }
                    if (line == null) break
                    line = line.trim()
                    if (line.isEmpty()) continue
                    processUpstreamLine(line)
                }
            } catch (e: Exception) {
                if (running) onLog.log("[Proxy!] upstream error: ${e.message}")
            } finally {
                synchronized(lock) {
                    try { sock?.close() } catch (_: Exception) { }
                    if (upstreamSock === sock) upstreamSock = null
                }
                upstreamConnected = false
                if (running && requirementActive()) {
                    // retry after a pause unless the requirement is gone
                    Thread.sleep(5000)
                    executor.execute { ensureUpstream() }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun closeUpstream() {
        synchronized(lock) {
            try { upstreamSock?.close() } catch (_: Exception) { }
            upstreamSock = null
            upstreamConnected = false
            effectiveCallsign = ""
        }
    }

    private fun processUpstreamLine(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        val low = text.lowercase()
        if (text.startsWith("#") || low.startsWith("user ") || low.startsWith("logresp") ||
            low.startsWith("ping")) return
        synchronized(statsLock) { rxInternet++ }
        pushPacket("RX<-internet", text)
        // internet -> all local clients (as-is)
        broadcastToClients(text)
        // internet -> radio (as-is)
        if (cfg.internetToRadio) {
            try { onTxToRadio.onText(text) } catch (_: Exception) { }
            synchronized(statsLock) { txRadio++ }
        }
        bumpStats()
    }

    // --------------------------------------------------------- broadcast / cache
    private fun broadcastToClients(text: String) {
        val payload = (text + "\r\n").toByteArray(Charsets.UTF_8)
        synchronized(statsLock) { txClients++ }
        for (c in clients) {
            try {
                val out = c.socket.getOutputStream()
                out.write(payload); out.flush()
            } catch (_: Exception) { }
        }
        bumpStats()
    }

    private fun sendLine(out: OutputStream, s: String) {
        try {
            out.write((s + "\r\n").toByteArray()); out.flush()
        } catch (_: Exception) { }
    }

    /** Send a packet toward the internet; on disconnect buffer it (capped). */
    private fun sendToInternet(text: String) {
        synchronized(statsLock) { txInternet++ }
        pushPacket("TX->internet", text)
        if (upstreamConnected) {
            try {
                val s = upstreamSock ?: return
                synchronized(lock) {
                    s.getOutputStream().write((text + "\r\n").toByteArray())
                    s.getOutputStream().flush()
                }
            } catch (_: Exception) {
                bufferPacket(text)
            }
        } else {
            bufferPacket(text)
        }
        bumpStats()
    }

    private fun bufferPacket(text: String) {
        synchronized(cacheLock) {
            cache.add(Cached(text, System.currentTimeMillis() + cfg.cacheSeconds * 1000))
        }
    }

    private fun flushCache(out: OutputStream) {
        val now = System.currentTimeMillis()
        val toSend = mutableListOf<String>()
        synchronized(cacheLock) {
            val it = cache.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c.expiry <= now) { it.remove(); continue }
                toSend.add(c.payload); it.remove()
            }
        }
        for (p in toSend) sendLine(out, p)
        if (toSend.isNotEmpty()) onLog.log("[Proxy] flushed ${toSend.size} buffered packet(s)")
    }

    // ----------------------------------------------------------------- idle/prune
    private fun idleLoop() {
        var noReqSince = 0L
        var lastClientPing = 0L
        while (running) {
            try { Thread.sleep(2000) } catch (_: Exception) { break }
            val now = System.currentTimeMillis()
            // prune expired cache
            synchronized(cacheLock) {
                val it = cache.iterator()
                while (it.hasNext()) if (it.next().expiry <= now) it.remove()
            }
            // keepalive ping to the local clients, same interval as upstream
            if (clients.isNotEmpty() && now - lastClientPing >= cfg.pingSeconds * 1000) {
                broadcastToClients("# ping (${cfg.igateName})")
                lastClientPing = now
            }
            // upstream idle-out when no requirement for idleTimeoutSeconds
            if (requirementActive()) {
                noReqSince = 0L
            } else {
                if (noReqSince == 0L) noReqSince = now
                if (now - noReqSince >= cfg.idleTimeoutSeconds * 1000) {
                    closeUpstream()
                    onLog.log("[Proxy] upstream idle (no requirement) for ${cfg.idleTimeoutSeconds}s, closed")
                    noReqSince = now
                }
            }
        }
    }

    private fun appendToPath(frame: String, suffix: String): String {
        val idx = frame.indexOf(':')
        val head = if (idx >= 0) frame.substring(0, idx) else frame
        val info = if (idx >= 0) frame.substring(idx + 1) else ""
        if (!head.contains(">")) return frame
        val gt = head.indexOf('>')
        val left = head.substring(0, gt)
        val rest = head.substring(gt + 1)
        return if (info.isNotEmpty()) "$left>$rest,$suffix:$info" else "$left>$rest,$suffix"
    }

    /** One connected local APRS-IS client with its parsed login metadata. */
    private class LocalClient(val socket: Socket) {
        val address: String get() = socket.inetAddress?.hostAddress ?: "?"
        val port: Int get() = socket.port
        @Volatile var callsign: String = ""
        @Volatile var passcode: Int = -1
        @Volatile var filter: String = ""
        @Volatile var verified: Boolean = false
    }

    companion object {
        /** Standard APRS-IS passcode algorithm (same as Python aprslib). */
        fun computePasscode(callsign: String): Int {
            val core = callsign.substringBefore("-").filter { it.isLetterOrDigit() }.uppercase()
            var code = 0x73E2
            for ((i, ch) in core.withIndex()) {
                val shift = if (i % 2 == 0) 8 else 0
                code = code xor (ch.code shl shift)
            }
            return code and 0x7FFF
        }
    }
}