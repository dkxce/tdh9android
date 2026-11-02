/*
 ==============================================================================
  AX.25 UI-frame decoder / encoder (binary <-> readable APRS text)
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  AX.25 binary frame encode/decode to/from readable APRS text.
            Mirrors the Python reference (same address layout and control/PID).
  Russian:  Кодирование/декодирование бинарных AX.25-кадров в читаемый
            APRS-текст и обратно.

  AX.25 address block: dest(7) source(7) repeaters(7 each). Each address is
  7 bytes: 6 chars left-shifted by 1, then SSID (bits 1..4) + last-flag (bit 0).
  UI frames use control 0x03 and PID 0xF0 (no layer 3).
 ==============================================================================
 */

package com.dkxce.radio_bridge.core

import java.io.ByteArrayOutputStream

/**
 * AX.25 address block layout: destination(7) source(7) repeaters(7 each).
 * Each address is 7 bytes: 6 chars left-shifted by 1, then a byte with
 * SSID in bits 1..4 and 'last address' flag in bit 0.
 *
 * Mirrors the Python `ax25.py`.
 */
data class Ax25Frame(
    val source: String = "",
    val destination: String = "",
    val repeaters: List<String> = emptyList(),
    val control: Int = 0,
    val pid: Int? = null,
    val info: ByteArray = ByteArray(0),
    val isUi: Boolean = false,
) {
    fun headerText(): String {
        val sb = StringBuilder("$source>$destination")
        if (repeaters.isNotEmpty()) sb.append(",").append(repeaters.joinToString(","))
        return sb.toString()
    }

    fun toText(): String {
        val infoStr = String(info, Charsets.ISO_8859_1)
        return if (infoStr.isNotEmpty()) "${headerText()}:$infoStr" else headerText()
    }
}

object Ax25 {
    private const val UI_CONTROL = 0x03
    private const val PID_NO_LAYER3 = 0xF0

    private fun decodeCallsign(addr: ByteArray): String {
        if (addr.size < 7) return ""
        val chars = StringBuilder()
        for (i in 0 until 6) {
            val b = addr[i].toInt() and 0xFF
            if (b == 0) continue
            val c = (b shr 1).toChar()
            if (c == ' ') continue
            chars.append(c)
        }
        val ssid = (addr[6].toInt() shr 1) and 0x0F
        return if (ssid != 0) "${chars}-$ssid" else chars.toString()
    }

    /** Decode a binary AX.25 payload; returns null if not plausible. */
    fun decode(data: ByteArray): Ax25Frame? {
        if (data.size < 15) return null
        var i = 0
        val destB = data.copyOfRange(i, i + 7); i += 7
        val srcB = data.copyOfRange(i, i + 7); i += 7
        val dst = decodeCallsign(destB)
        val src = decodeCallsign(srcB)
        if (src.isEmpty() || dst.isEmpty()) return null

        var ext = srcB[6].toInt() and 0x01
        val repeaters = ArrayList<String>()
        var control = 0
        var pid: Int? = null
        var isUi = false
        var info = ByteArray(0)

        while ((ext and 0x01) == 0) {
            if (i + 7 > data.size) break
            val d = data.copyOfRange(i, i + 7); i += 7
            repeaters.add(decodeCallsign(d))
            ext = d[6].toInt() and 0x01
        }

        if (i < data.size) {
            control = data[i].toInt() and 0xFF; i++
            if ((control and 0x01) != 0) { // UI / unnumbered
                if (i < data.size) { pid = data[i].toInt() and 0xFF; i++ }
                isUi = true
            }
        }
        if (i < data.size) info = data.copyOfRange(i, data.size)

        return Ax25Frame(
            source = src, destination = dst, repeaters = repeaters,
            control = control, pid = pid, info = info, isUi = isUi,
        )
    }

    private fun encodeCallsign(call: String, ssidIn: Int, last: Boolean): ByteArray {
        var ssid = ssidIn
        var core = call.trim()
        val dash = core.lastIndexOf('-')
        if (dash != -1) {
            val tail = core.substring(dash + 1)
            if (tail.isNotEmpty() && tail.all { it.isDigit() }) {
                ssid = tail.toInt()
                core = core.substring(0, dash)
            } else if (tail.isEmpty()) {
                core = core.substring(0, dash)
            }
        }
        if (core.length > 6) core = core.substring(0, 6)
        core = core.padEnd(6, ' ')
        val out = ByteArrayOutputStream()
        for (ch in core) out.write(ch.code shl 1)
        out.write(((ssid and 0x0F) shl 1) or (if (last) 1 else 0))
        return out.toByteArray()
    }

    private fun parseCallsigns(text: String): Triple<String, String, List<String>> {
        var t = text.trim()
        val colon = t.indexOf(':')
        if (colon != -1) t = t.substring(0, colon)
        val arrow = t.indexOf('>')
        if (arrow == -1) return Triple("", "", emptyList())
        val src = t.substring(0, arrow).trim()
        val parts = t.substring(arrow + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val dst = parts.getOrElse(0) { "" }
        val reps = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()
        return Triple(src, dst, reps)
    }

    /**
     * Build a binary AX.25 UI-frame from APRS text 'SRC>DST,PATH:info'
     * (or just 'SRC:info' / 'SRC').
     */
    fun encodeText(text: String, defaultDst: String = "APRS", infoPrefix: String = ""): ByteArray {
        var t = text.trim()
        val (src, dst0, repeaters) = parseCallsigns(t)
        if (src.isEmpty()) throw IllegalArgumentException("Source callsign missing")
        var info: String
        val colon = t.indexOf(':')
        info = if (colon != -1) t.substring(colon + 1) else ""
        val dst = if (dst0.isEmpty()) defaultDst else dst0
        if (infoPrefix.isNotEmpty() && info.isNotEmpty()) info = infoPrefix + info

        val body = ByteArrayOutputStream()
        if (repeaters.isEmpty()) {
            body.write(encodeCallsign(dst, 0, false))
            body.write(encodeCallsign(src, 0, true))
        } else {
            body.write(encodeCallsign(dst, 0, false))
            body.write(encodeCallsign(src, 0, false))
            for ((i, r) in repeaters.withIndex()) {
                body.write(encodeCallsign(r, 0, i == repeaters.size - 1))
            }
        }
        body.write(UI_CONTROL)
        body.write(PID_NO_LAYER3)
        body.write(info.toByteArray(Charsets.ISO_8859_1))
        return body.toByteArray()
    }

    /** Decode payload into readable 'SRC>DST,..:info', else raw ASCII. */
    fun toAprsText(data: ByteArray): String {
        val frame = decode(data)
        if (frame != null && frame.headerText().isNotEmpty()) return frame.toText()
        return String(data, Charsets.ISO_8859_1).trim()
    }
}