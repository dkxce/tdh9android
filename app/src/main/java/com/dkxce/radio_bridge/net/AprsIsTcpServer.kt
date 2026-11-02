/*
 * APRS-IS compatible TCP server (text protocol + passcode check).
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge.net

import com.dkxce.radio_bridge.core.Ax25
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * APRS-IS compatible server. Clients log in with
 * `user CALL pass PASSCODE vers ... filter ...`. Passcode verification is
 * mandatory (standard APRS-IS algorithm); failed login => read-only. Received
 * radio frames are broadcast to all clients as UTF-8 text lines.
 */
class AprsIsTcpServer(
    port: Int,
    private val onTx: (ByteArray) -> Unit,
    onClients: () -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : BaseTcpServer(port, onClients, "aprs"), FrameBroadcaster {

    private val peers = java.util.concurrent.ConcurrentHashMap<TcpClient, Pair<String, Boolean>>()

    private val SERVER_ID = "TIDRADIO-BRIDGE"

    override fun handleClient(c: TcpClient) {
        val out: OutputStream = c.socket.getOutputStream()
        try {
            sendLine(out, "# SimpleAPRSServer startup")
            val reader = BufferedReader(InputStreamReader(c.socket.getInputStream(), Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val text = line!!.trim()
                if (text.isEmpty()) continue
                handleLine(c, out, text)
            }
        } catch (_: IOException) {
        } catch (_: Exception) {
        } finally {
            peers.remove(c)
            removeClient(c)
        }
    }

    private fun handleLine(c: TcpClient, out: OutputStream, text: String) {
        val parts = text.split(" ")
        if (parts.isNotEmpty() && parts[0].equals("user", true)) {
            // user CALL pass PASSCODE vers ...
            var call = ""; var passStr = ""
            for (i in 1 until parts.size) {
                val p = parts[i]
                when (p.lowercase()) {
                    "pass" -> if (i + 1 < parts.size) passStr = parts[i + 1]
                    "vers" -> return // ignore the rest of login line
                    else -> if (call.isEmpty() && !p.equals("filter", true) && p != "vers" && p != "pass") call = p
                }
            }
            val passCode = passStr.toIntOrNull() ?: 0
            val ok = (passCode != 0) && (computePasscode(call) == passCode)
            peers[c] = call to ok
            notifyClientsChanged() // refresh client list (callsign + tx/rx) in UI
            sendLine(out, "# logresp $call ${if (ok) "verified" else "unverified"}, server $SERVER_ID")
            if (ok) onTx("$call>APRS:$SERVER_ID online".toByteArray(Charsets.UTF_8))
            return
        }

        // If the client never sent "user ..." login, derive the callsign from
        // its first APRS packet ("CALL>DST,...") so the client list shows it.
        if (peers[c] == null && text.isNotEmpty() && !text.startsWith("#") && !text.startsWith("user")) {
            val call = text.substringBefore('>').trim()
            if (call.isNotEmpty()) {
                peers[c] = call to false
                notifyClientsChanged()
            }
        }

        // Plain APRS data line from a verified (or read-only) client -> TX.
        if (peers[c] != null) {
            // comments never go to the radio
            if (!text.startsWith("#") && text.isNotEmpty()) {
                onTx(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun sendLine(out: OutputStream, s: String) {
        try {
            out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) { }
    }

    /** APRS-IS client list: "aprs://CALLSIGN@ip:port/tx" if verified,
     * "aprs://CALLSIGN@ip:port/rx" if unverified (like the Python version). */
    override fun clientList(): List<String> {
        return clients.map { c ->
            val (call, verified) = peers[c] ?: ("" to false)
            val dir = if (verified) "tx" else "rx"
            "aprs://$call@${c.address}:${c.port}/$dir"
        }
    }

    override fun broadcastRadio(raw: ByteArray) {
        val txt = Ax25.toAprsText(raw)
        if (txt.isBlank()) return
        val payload = (txt.trim() + "\r\n").toByteArray(Charsets.UTF_8)
        for (c in clients) {
            try {
                val out: OutputStream = c.socket.getOutputStream()
                out.write(payload)
                out.flush()
            } catch (_: Exception) { }
        }
    }

    companion object {
        /** Standard APRS-IS passcode algorithm (same as Python aprslib). */
        fun computePasscode(callsign: String): Int {
            val core = (callsign.substringBefore("-")).filter { it.isLetterOrDigit() }.uppercase()
            var code = 0x73E2
            for ((i, ch) in core.withIndex()) {
                val shift = if (i % 2 == 0) 8 else 0
                code = code xor (ch.code shl shift)
            }
            return code and 0x7FFF
        }
    }
}