/*
 ==============================================================================
  IGate bridge: radio APRS <-> APRS-IS network
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  Standalone IGate bridging the radio APRS feed and the APRS-IS
            network. Port of the Python igate.py.

  Data flow (TCP as an APRS-IS client):
      Network -> Radio (a): a packet is forwarded to the radio only if it
          came with the IGate callsign <IGATE_NAME> in the path, does NOT
          contain qAR nor qAO constructs, and (optionally, depending on the
          checkTcpip flag) contains TCPIP.
      Radio -> Network (b): every packet received from the air is sent to
          APRS-IS with the path suffix ",qAR,<IGATE_NAME>".

  Client login:
      user <APRSIS_CLIENT> pass <passcode> vers TDH9KissIGate v1.0 filter <filter>
  Default filter:  filter d/<IGATE_NAME>*
  Russian:  Самостоятельный IGate между радиоэфиром и сетью APRS-IS.
 ==============================================================================
 */

package com.dkxce.radio_bridge.igate

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** IGate connection settings (mirrors Python igate.py DEFAULT_SETTINGS). */
data class IGateConfig(
    val igateClient: String = "NOCALL-10", // APRSIS_CLIENT callsign used to connect
    val igateName: String = "NOCALL-1",    // IGATE_NAME callsign shown to the network
    val passcode: Int = 0,
    val server: String = "rotate.aprs2.net",
    val port: Int = 14580,
    val filter: String = "",               // default = "d/<IGATE_NAME>*"
    val checkTcpip: Boolean = true,        // require TCPIP in path to gate to radio
    val checkNoQar: Boolean = true,        // require absence of qAR in path
    val checkNoQao: Boolean = true,        // require absence of qAO in path
    val enabled: Boolean = false,          // master switch
)

/** Callback fired with a decoded APRS frame (text) to transmit over the radio. */
fun interface IGateRxToRadio {
    fun onFrame(text: String)
}

class IGateClient(
    private var cfg: IGateConfig,
    private val onRxToRadio: IGateRxToRadio,
    private val onLog: (String) -> Unit = {},
) {

    private var sock: Socket? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    private val lock = Any()

    val isConnected: Boolean get() = connected

    fun start() {
        if (running) return
        running = true
        thread = Thread(::runLoop).apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        synchronized(lock) {
            try { sock?.close() } catch (_: Exception) { }
            sock = null
        }
        thread?.join(3000)
        thread = null
        connected = false
    }

    fun updateConfig(cfg: IGateConfig) {
        this.cfg = cfg
    }

    private fun log(msg: String) {
        try { onLog(msg) } catch (_: Exception) { }
    }

    private fun runLoop() {
        while (running) {
            if (!cfg.enabled) {
                Thread.sleep(1000)
                continue
            }
            try {
                connectAndLoop()
            } catch (e: Exception) {
                log("[IGate] error: ${e.message}")
                try { Thread.sleep(5000) } catch (_: Exception) { }
            }
        }
    }

    private fun connectAndLoop() {
        val server = cfg.server
        val port = cfg.port
        val client = cfg.igateClient
        val igateName = cfg.igateName
        var passcode = cfg.passcode
        val userFilter = if (cfg.filter.isNotEmpty()) cfg.filter else "d/${igateName.substringBefore('-')}*"

        if (passcode == 0) passcode = computePasscode(client)

        log("[IGate] connecting to $server:$port as $client")

        val s = Socket()
        try {
            s.connect(InetSocketAddress(server, port), 15000)
            s.soTimeout = 60000
            s.keepAlive = true
        } catch (e: Exception) {
            log("[IGate] connect error: ${e.message}")
            throw e
        }
        synchronized(lock) { sock = s }
        connected = true

        // Send login first; banner lines are ignored in the main loop.
        val login = "user $client pass $passcode vers TDH9KissIGate v1.0 filter $userFilter\r\n"
        log("[IGate] login: user $client pass $passcode vers TDH9KissIGate v1.0 filter $userFilter")
        try { s.getOutputStream().write(login.toByteArray()) } catch (e: Exception) { throw e }

        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running && cfg.enabled) {
                var line: String?
                try {
                    line = reader.readLine()
                } catch (te: java.net.SocketTimeoutException) {
                    // Keep the connection alive with a ping, like the Python
                    // IGate does on socket.timeout, so the server does not
                    // drop us during idle periods.
                    try {
                        s.getOutputStream().write("# ping\r\n".toByteArray())
                        s.getOutputStream().flush()
                    } catch (_: Exception) { break }
                    continue
                }
                if (line == null) break
                line = line.trim()
                if (line.isEmpty()) continue
                processServerLine(line)
            }
        } catch (e: Exception) {
            if (running) log("[IGate] read error: ${e.message}")
        } finally {
            log("[IGate] disconnected")
            try { s.close() } catch (_: Exception) { }
            synchronized(lock) { sock = null }
            connected = false
        }
    }

    private fun processServerLine(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        val low = text.lowercase()
        if (text.startsWith("#") || low.startsWith("user ") || low.startsWith("ping")) return
        // It is a packet -> decide whether to gate it to the radio.
        if (shouldGateToRadio(text)) {
            log("[IGate net -> radio] $text")
            try { onRxToRadio.onFrame(text) } catch (_: Exception) { }
        } else {
            log("[IGate net --skip-> radio] $text")
        }
    }

    private fun currentConfig(): IGateConfig = cfg

    /** Rule (a): whether to send this network packet to the radio. */
    private fun shouldGateToRadio(frame: String): Boolean {
        val c = currentConfig()
        val igateName = c.igateName.uppercase()
        val pathUpper = parsePacketParts(frame).path.joinToString(",").uppercase()

        if (igateName.isNotEmpty() && !pathUpper.contains(igateName)) return false
        if (c.checkTcpip && !pathUpper.contains("TCPIP")) return false
        if (c.checkNoQar && pathUpper.contains("QAR")) return false
        if (c.checkNoQao && pathUpper.contains("QAO")) return false
        return true
    }

    /** Rule (b): a frame received from the radio is sent to the network. */
    fun radioRx(text: String) {
        if (!connected || !cfg.enabled) return
        if (text.isBlank()) return
        val out = appendToPath(text, "qAR,${cfg.igateName}")
        log("[IGate radio -> net] $text  ->  $out")
        try {
            synchronized(lock) {
                sock?.getOutputStream()?.write((out + "\r\n").toByteArray())
            }
        } catch (e: Exception) {
            log("[IGate] tx error: ${e.message}")
        }
    }

    private fun appendToPath(frame: String, suffix: String): String {
        val (head, info) = splitHeader(frame)
        if (head.contains(">")) {
            val idx = head.indexOf('>')
            val leftPart = head.substring(0, idx)
            val restPart = head.substring(idx + 1)
            return if (info.isNotEmpty())
                "$leftPart>$restPart,$suffix:$info"
            else
                "$leftPart>$restPart,$suffix"
        }
        return frame
    }

    private data class PacketParts(val src: String, val dst: String, val path: List<String>, val info: String)

    private fun parsePacketParts(frame: String): PacketParts {
        val (head, info) = splitHeader(frame)
        var src = ""
        var rest = ""
        if (head.contains(">")) {
            val idx = head.indexOf('>')
            src = head.substring(0, idx)
            rest = head.substring(idx + 1)
        }
        var dst = ""
        val path = mutableListOf<String>()
        if (rest.isNotEmpty()) {
            val pieces = rest.split(",")
            dst = pieces[0]
            for (i in 1 until pieces.size) if (pieces[i].isNotEmpty()) path.add(pieces[i])
        }
        return PacketParts(src.trim(), dst.trim(), path, info)
    }

    private fun splitHeader(frame: String): Pair<String, String> {
        val idx = frame.indexOf(':')
        return if (idx >= 0) frame.substring(0, idx) to frame.substring(idx + 1) else frame to ""
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