/*
 ==============================================================================
  APRS-IS compatible TCP server (text protocol + passcode check)
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  APRS-IS compatible server (port 14580). Clients log in with
            "user CALL pass PASSCODE vers ...". Passcode verification is
            mandatory; verified clients get "/tx", unverified "/rx" in the
            client list. Mirrors the Python reference aprsis_server.
  Russian:  Сервер, совместимый с протоколом APRS-IS (порт 14580).
 ==============================================================================
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

    private val SERVER_ID = "TIDRADIO-BRIDGE"

    override fun handleClient(c: TcpClient) {
        val out: OutputStream = c.socket.getOutputStream()
        try {
            // Welcome banner like the Python reference server, so strict
            // APRS-IS clients send their "user ..." login right after it.
            sendLine(out, "# ${SERVER_ID}: connection accepted")
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
            removeClient(c)
        }
    }

    private fun handleLine(c: TcpClient, out: OutputStream, text: String) {
        // On every meaningful input from the client, refresh the client list so
        // a late login/derived callsign is always reflected in the UI.
        val parts = text.split(" ")
        if (parts.isNotEmpty() && parts[0].equals("user", true)) {
            // Login: "user CALL pass PASSCODE vers ... filter ..." (like Python).
            // Walk the whole line collecting CALLSIGN and PASSCODE by keyword
            // position; do NOT return early on "vers" (that would skip saving
            // the callsign for standard clients that include `vers`).
            var callsign = ""
            var passcodeRaw = ""
            for (i in parts.indices) {
                val p = parts[i]
                when (p.lowercase()) {
                    "user" -> if (i + 1 < parts.size && callsign.isEmpty()) callsign = parts[i + 1]
                    "pass" -> if (i + 1 < parts.size) passcodeRaw = parts[i + 1]
                    else -> { /* ignore */ }
                }
            }
            val passCode = passcodeRaw.toIntOrNull() ?: -1
            val ok = (passCode != -1) && (computePasscode(callsign) == passCode)
            c.callsign = callsign
            c.verified = ok
            onLog?.invoke("[APRS] login: user=$callsign pass=$passCode verified=$ok")
            notifyClientsChanged() // refresh client list (callsign + tx/rx) in UI
            sendLine(out, "# logresp $callsign ${if (ok) "verified" else "unverified"}, server $SERVER_ID")
            if (ok) onTx("$callsign>APRS:$SERVER_ID online".toByteArray(Charsets.UTF_8))
            return
        }

        // If the client never sent "user ..." login, derive the callsign from
        // its first APRS packet ("CALL>DST,...") so the client list shows it.
        if (c.callsign.isEmpty() && text.isNotEmpty() && !text.startsWith("#") && !text.startsWith("user")) {
            val call = text.substringBefore('>').trim()
            if (call.isNotEmpty()) {
                c.callsign = call
                notifyClientsChanged()
            }
        }

        // Plain APRS data line from a client that logged in or sent data.
        if (c.callsign.isNotEmpty()) {
            // comments never go to the radio
            if (!text.startsWith("#") && text.isNotEmpty()) {
                // Mirror Python _client_tx_aprs: encode the text APRS frame back
                // into a binary AX.25 UI-frame before transmitting to the radio.
                // Sending the raw UTF-8 text bytes produced garbage on the
                // receiving radios.
                try {
                    onTx(Ax25.encodeText(text))
                } catch (e: Exception) {
                    onLog?.invoke("[APRS!] failed to build frame: ${e.message}")
                }
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
            val dir = if (c.verified) "tx" else "rx"
            val entry = "aprs://${c.callsign}@${c.address}:${c.port}/$dir"
            onLog?.invoke("[APRS] clientList: call='${c.callsign}' verified=${c.verified} -> $entry")
            entry
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