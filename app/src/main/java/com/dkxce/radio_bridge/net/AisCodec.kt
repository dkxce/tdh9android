/*
 ==============================================================================
  APRS -> AIS converter: builds NMEA !AIVDM frames from APRS position packets
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.0.0.0

  English:  A read-only AIS feed is generated from received APRS position
            packets. Each packet is reduced to (callsign, lat, lon, course,
            speed) and encoded as AIS class A positional (types 1-3) plus a
            static/voyage (type 5) report, exactly like the OruxPalsServer
            does. A deterministic 24-bit MMSI is derived from the callsign so
            the same object maps to the same ship id on every frame.

            Frame format sent to AIS clients:
                !AIVDM,1,1,,A,<payload>,0*<checksum>

            Reference implementations (APRS->AIS bit layout) were studied from:
              https://github.com/dkxce/SimpleAPRSServer
              https://github.com/dkxce/OruxPalsServer

  Russian:  Генерация read-only AIS потока из принятых APRS позиций. Каждый
            пакет сводится к (callsign, lat, lon, course, speed) и кодируется
            как AIS класс A (типы 1-3) плюс статический (тип 5) отчёт, как в
            OruxPalsServer. Из callsign детерминированно выводится 24-битный
            MMSI, чтобы объект всегда имел один и тот же номер судна.
 ==============================================================================
 */

package com.dkxce.radio_bridge.net

/**
 * A single decoded APRS position, flattend to the AIS-relevant fields.
 * Speed is stored in knots (as transmitted in the APRS course/speed field).
 */
data class AisPosition(
    val callsign: String,     // object / station callsign (upper case)
    val lat: Double,
    val lon: Double,
    val course: Int,          // degrees, 0..359
    val speedKnots: Int,      // knots
)

/**
 * NMEA AIVDM frame builder (encodes only; the proxy decides what to broadcast).
 */
object AisCodec {
    // 6-bit AIS character table (same as OruxPalsServer / bosunsmate.org)
    private const val TABLE = "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#$%&'()*+,-./0123456789:;<=>?"

    /**
     * Deterministic 24-bit MMSI from a callsign (same hash as the reference
     * servers so objects map to stable, unique ship ids).
     */
    fun mmsi(callsign: String): Long {
        val up = callsign.uppercase().padEnd(9, ' ')
        var hash = 2017
        var i = 0
        while (i < 9) {
            hash = hash xor (up[i].code shl 16)
            hash = hash xor (up[i + 1].code shl 8)
            hash = hash xor up[i + 2].code
            i += 3
        }
        return (hash and 0xFFFFFF).toLong()
    }

    /**
     * Builds the full NMEA line for a class A positional report (type 1).
     */
    fun positionFrame(pos: AisPosition): String {
        val m = mmsi(pos.callsign)
        val b = ByteArray(21) // 168 bits
        setBits(b, 0, 6, 1)                       // type 1
        setBits(b, 6, 2, 0)                       // repeat
        setBits(b, 8, 30, m)                      // MMSI
        setBits(b, 38, 4, 15)                     // navigation status: not defined
        setBits(b, 42, 8, 0)                      // rate of turn
        setBits(b, 50, 10, (pos.speedKnots.coerceIn(0, 102) * 10L)) // SOG, tenths of kn
        setBits(b, 60, 1, 1)                      // accuracy
        setBits(b, 61, 28, (pos.lon * 600000).toLong())            // lon -1e4 deg
        setBits(b, 89, 27, (pos.lat * 600000).toLong())            // lat
        setBits(b, 116, 12, (pos.course.coerceIn(0, 359) * 10L))   // COG tenths deg
        setBits(b, 128, 9, pos.course.coerceIn(0, 359).toLong())   // HDG
        setBits(b, 137, 6, 60)                    // second: not available
        setBits(b, 143, 2, 0)                     // maneuver
        setBits(b, 149, 19, 0)                    // radio status
        return frame(encode(b))
    }

    /**
     * Builds the full NMEA line for a static & voyage related report (type 5).
     */
    fun staticFrame(pos: AisPosition): String {
        val m = mmsi(pos.callsign)
        val b = ByteArray(54) // 424+ bits
        setBits(b, 0, 6, 5)                       // type 5
        setBits(b, 6, 2, 0)                       // repeat
        setBits(b, 8, 30, m)                      // MMSI
        setBits(b, 38, 2, 0)                      // AIS version
        setBits(b, 40, 30, m)                     // IMO (reuse MMSI)
        setString(b, 70, 7, pos.callsign)         // call sign
        setString(b, 112, 20, pos.callsign)       // vessel name
        setBits(b, 232, 8, 0)                     // ship type: not available
        setBits(b, 240, 9, 4)                     // A
        setBits(b, 249, 9, 1)                     // B
        setBits(b, 258, 6, 1)                     // C
        setBits(b, 264, 6, 2)                     // D
        setBits(b, 270, 4, 1)                     // position fix type
        // ETA (UTC): now
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        setBits(b, 274, 4, (cal.get(java.util.Calendar.MONTH) + 1).toLong())
        setBits(b, 278, 5, cal.get(java.util.Calendar.DAY_OF_MONTH).toLong())
        setBits(b, 283, 5, cal.get(java.util.Calendar.HOUR_OF_DAY).toLong())
        setBits(b, 288, 6, cal.get(java.util.Calendar.MINUTE).toLong())
        setString(b, 302, 20, pos.callsign)       // destination
        setBits(b, 422, 1, 0)                     // DTE
        return frame(encode(b))
    }

    /** Two lines (type 1 + type 5) terminated with CRLF, ready to broadcast. */
    fun frames(pos: AisPosition): String =
        positionFrame(pos) + "\r\n" + staticFrame(pos) + "\r\n"

    /** Parse an APRS-IS text line and extract a position, or null when no valid fix. */
    fun parseAprsPosition(line: String): AisPosition? {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        val gt = l.indexOf('>')
        if (gt <= 1) return null
        val colon = l.indexOf(':', gt)
        if (colon < gt) return null
        val callsign = l.substring(0, gt).trim()
        val pkt = l.substring(colon)
        if (pkt.length < 2) return null
        val sym = pkt[1]

        // Object ';' and Item ')': take the object name as the callsign, then
        // re-parse the remainder as a plain position (same as reference server).
        if (sym == ';' || sym == ')') {
            val limit = pkt.substring(2, Math.min(pkt.length, 12))
            val endA = limit.indexOf('*'); val endB = limit.indexOf('_')
            val endIdx = (if (endA < 0 && endB < 0) -1 else
                if (endA < 0) endB + 2 else if (endB < 0) endA + 2 else Math.min(endA, endB) + 2)
            if (endIdx < 2) return null
            val name = pkt.substring(2, endIdx).trim()
            if (name.isEmpty()) return null
            return parseAprsPosition(name + ">" + pkt.substring(endIdx + 1))
        }

        if (sym != '!' && sym != '=' && sym != '/' && sym != '@') return null

        var pos = pkt.substring(2)
        if (pos.isEmpty() || pos[0] == '!') return null
        // Compressed data begins with '/', otherwise handle time stamps.
        if (pos[0] != '/' && pkt.length >= 9) {
            when (pkt[8]) {
                'z', '/', 'h' -> pos = pkt.substring(9)
            }
        }
        if (pos.isEmpty()) return null

        var lat = 0.0
        var lon = 0.0
        var course = 0
        var speed = 0

        if (pos[0] == '/') {
            // compressed (rare)
            if (pos.length < 13) return null
            lat = 90.0 - (((pos[1].code - 33) * 91.0 * 91 * 91 +
                (pos[2].code - 33) * 91.0 * 91 +
                (pos[3].code - 33) * 91.0 + (pos[4].code - 33)) / 380926.0)
            lon = -180.0 + (((pos[5].code - 33) * 91.0 * 91 * 91 +
                (pos[6].code - 33) * 91.0 * 91 +
                (pos[7].code - 33) * 91.0 + (pos[8].code - 33)) / 190463.0)
            var cmp = pos.substring(10, 12)
            if (cmp[0] == '_') cmp = pos.substring(11, 13) // weather: shift
            if (cmp[0] != ' ' && cmp[0] != '{' && cmp[0] != '|') {
                course = (cmp[0].code - 33) * 4
                speed = (Math.pow(1.08, (cmp[1].code - 33).toDouble()) - 1).toInt()
            }
        } else {
            // uncompressed
            if (pos.length < 19) return null
            if (pos.substring(0, 18).contains(" ")) return null
            lat = pos.substring(0, 2).toDouble() + pos.substring(2, 7).toDouble() / 60.0
            if (pos[7] == 'S') lat = -lat
            lon = pos.substring(9, 12).toDouble() + pos.substring(12, 17).toDouble() / 60.0
            if (pos[17] == 'W') lon = -lon
            val symbol = pos[18]
            var after = pos.substring(19)
            if (symbol != '_' && after.length >= 7 && after[3] == '/') {
                course = after.substring(0, 3).toIntOrNull() ?: 0
                speed = after.substring(4, 7).toIntOrNull() ?: 0 // knots
                after = after.substring(7)
            }
        }

        if (lat == 0.0 && lon == 0.0) return null
        return AisPosition(callsign.uppercase(), lat, lon, course, speed)
    }

    // --------------------------------------------------------- low-level bits
    private fun mask(len: Int): Long = if (len >= 64) -1L else (1L shl len) - 1

    private fun setBits(b: ByteArray, start: Int, len: Int, value: Long) {
        val v = value and mask(len)
        for (k in 0 until len) {
            val bit = start + k
            val iByte = bit / 8
            val iBit = 7 - (bit % 8)
            if (((v shr (len - 1 - k)) and 1L) != 0L) {
                b[iByte] = (b[iByte].toInt() or (1 shl iBit)).toByte()
            }
        }
    }

    private fun setString(b: ByteArray, start: Int, chars: Int, value: String) {
        var s = value.uppercase()
        if (s.length > chars) s = s.substring(0, chars)
        s = s.padEnd(chars, ' ')
        for (i in 0 until chars) {
            val idx = TABLE.indexOf(s[i])
            if (idx >= 0) setBits(b, start + i * 6, 6, idx.toLong())
        }
    }

    /** Pack the bit buffer into a 6-bit AIS character payload (mirrors ref). */
    private fun encode(ba: ByteArray): String {
        val sb = StringBuilder()
        for (i in ba.indices) {
            var v = 0
            var v2 = 0
            when (i % 3) {
                0 -> v = ((ba[i].toInt()) shr 2) and 0x3F
                1 -> v = ((ba[i - 1].toInt() and 0x03) shl 4) or ((ba[i].toInt() and 0xF0) shr 4)
                2 -> {
                    v = ((ba[i - 1].toInt() and 0x0F) shl 2) or ((ba[i].toInt() and 0xC0) shr 6)
                    v2 = (ba[i].toInt() and 0x3F) + 48
                    if (v2 > 87) v2 += 8
                }
            }
            v += 48
            if (v > 87) v += 8
            sb.append(v.toChar())
            if (i % 3 == 2) sb.append(v2.toChar())
        }
        return sb.toString()
    }

    /** NMEA checksum: XOR of every char between '!'/'$' and '*'. */
    private fun checksum(sentence: String): String {
        var from = 0
        if (sentence.startsWith('!') || sentence.startsWith('$')) from = 1
        val star = sentence.lastIndexOf('*')
        val to = if (star >= 0 && star == sentence.length - 3) star else sentence.length
        var sum = 0
        for (i in from until to) sum = sum xor sentence[i].code
        return (sum and 0xFF).toString(16).uppercase().padStart(2, '0')
    }

    private fun frame(payload: String): String {
        val s = "!AIVDM,1,1,,A,$payload,0"
        return "$s*${checksum(s)}"
    }
}