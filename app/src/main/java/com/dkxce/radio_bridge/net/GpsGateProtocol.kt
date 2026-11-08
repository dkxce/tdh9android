/*
 ==============================================================================
  GPSGate protocol helpers: $FRCMD parsing, CRC-64/ECMA, tracker packets
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.3.0

  English:  Franson GPSGate packets arrive as HTTP GET /?cmd=$FRCMD,... .
            This helper parses them into a position, derives a 16-char
            lowercase hex IMEI via CRC-64/ECMA-182 (poly 0x42F0E1EBA9EA3693)
            and builds outbound packets for the three supported protocols:
            rtt006, MT60 (Meitrack), GL200 (Queclink).

  Sources studied: the -net-proto folder (Mt60Packet.java, Rtt006Packet.java,
                   Gl200Packet.java) and its Python send_*.py examples.

  Russian:  Пакеты Franson GPSGate приходят как HTTP GET /?cmd=$FRCMD,... .
            Парсер извлекает позицию, получает 16-значный lowercase hex IMEI
            через CRC-64/ECMA-182 (полином 0x42F0E1EBA9EA3693) и строит
            исходящие пакеты для трёх протоколов: rtt006, MT60, GL200.
 ==============================================================================
 */

package com.dkxce.radio_bridge.net

import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/** A position decoded from a $FRCMD GPSGate packet. */
data class FrcmdPosition(
    val senderId: String,   // 667383866150 or 2366787 ...
    val lat: Double,        // degrees
    val lon: Double,
    val speedKmh: Double,   // already km/h (value in packet)
    val course: Double,     // degrees
    val altitude: Double,   // meters
    val dateDdmmyy: String = "",  // raw DDMMYY from the packet
    val timeHhmmss: String = "",  // raw HHMMSS from the packet
)

object GpsGateProtocol {

    // ------------------------------------------------------------------ FRCMD
    // $FRCMD,<senderId>,_SendMessage,,<lat>,<N/S>,<lon>,<E/W>,<speed>,<course>,<alt>,<dateDDMMYY>,<time>,<sats>
    fun parseFrcmd(text: String): FrcmdPosition? {
        val t = text.trim()
        if (!t.startsWith("\$FRCMD")) return null
        val p = t.split(",")
        // index: 0=$FRCMD 1=senderId 2=_SendMessage 3=empty 4=lat 5=NS 6=lon 7=EW 8=speed 9=course 10=alt 11=date 12=time 13=sats
        if (p.size < 11) return null
        val sender = p[1].trim()
        val lat = p.getOrNull(4)?.trim()?.toDoubleOrNull() ?: return null
        val ns = p.getOrNull(5)?.trim()?.uppercase()
        val lon = p.getOrNull(6)?.trim()?.toDoubleOrNull() ?: return null
        val ew = p.getOrNull(7)?.trim()?.uppercase()
        val speed = p.getOrNull(8)?.trim()?.toDoubleOrNull() ?: 0.0
        val course = p.getOrNull(9)?.trim()?.toDoubleOrNull() ?: 0.0
        val alt = p.getOrNull(10)?.trim()?.toDoubleOrNull() ?: 0.0
        val date = p.getOrNull(11)?.trim() ?: ""
        val time = p.getOrNull(12)?.trim() ?: ""
        // The $FRCMD payload may carry coordinates either as decimal degrees
        // ("55.7558") or as NMEA ("4435.08340" = 44 deg 35.08340 min).
        // NMEA lat has ≥3 digits before '.', NMEA lon has ≥4; convert to degrees.
        val latVal = if (nmeaLike(lat, 3)) nmeaToDeg(lat) else lat
        val lonVal = if (nmeaLike(lon, 4)) nmeaToDeg(lon) else lon
        return FrcmdPosition(
            senderId = sender,
            lat = if (ns == "S") -latVal else latVal,
            lon = if (ew == "W") -lonVal else lonVal,
            speedKmh = speed,
            course = course,
            altitude = alt,
            dateDdmmyy = date,
            timeHhmmss = time,
        )
    }

    // ------------------------------------------------------------------ coords
    /** True when [v] looks like NMEA DDMM.mmmm (integer part length >= [minIntDigits]). */
    private fun nmeaLike(v: Double, minIntDigits: Int): Boolean {
        val s = java.math.BigDecimal.valueOf(v).toPlainString()
        val intPart = s.substringBefore('.')
        return intPart.length >= minIntDigits
    }

    /** NMEA DDMM.mmmm -> decimal degrees. */
    private fun nmeaToDeg(v: Double): Double {
        val d = Math.floor(v / 100.0)
        val m = v - d * 100.0
        return d + m / 60.0
    }

    // ------------------------------------------------------------- timestamp
    /**
     * Interpret the raw $FRCMD dateDDMMYY/time fields per [mode] and return a
     * Calendar whose fields are already in the target time zone:
     *
     *  - UTC_NO_FIX:    the packet time is UTC          -> emitted as UTC.
     *  - LOCAL_FIX_UTC: the packet time is system-local -> converted to UTC.
     *  - UTC_FIX_LOCAL: the packet time is UTC          -> converted to system-local.
     *
     * Returns null when the fields are absent or malformed.
     */
    fun resolveTimestamp(pos: FrcmdPosition, mode: GpsGateTimestampMode): java.util.Calendar? {
        val dd = pos.dateDdmmyy
        // time may carry milliseconds (e.g. "230002.000"): take the whole
        // HHMMSS part before any '.' and tolerate surrounding whitespace
        val rawTime = pos.timeHhmmss.trim()
        val tt = rawTime.substringBefore('.')
        if (dd.length != 6 || tt.length != 6) return null
        val day = dd.substring(0, 2).toIntOrNull() ?: return null
        val month = dd.substring(2, 4).toIntOrNull() ?: return null
        val year = 2000 + (dd.substring(4, 6).toIntOrNull() ?: return null)
        val hour = tt.substring(0, 2).toIntOrNull() ?: return null
        val min = tt.substring(2, 4).toIntOrNull() ?: return null
        val sec = tt.substring(4, 6).toIntOrNull() ?: return null
        // source zone: how the packet time is interpreted
        val srcZone = when (mode) {
            GpsGateTimestampMode.UTC_NO_FIX, GpsGateTimestampMode.UTC_FIX_LOCAL ->
                java.util.TimeZone.getTimeZone("UTC")
            GpsGateTimestampMode.LOCAL_FIX_UTC -> java.util.TimeZone.getDefault()
        }
        val cal = java.util.Calendar.getInstance(srcZone)
        cal.clear()
        cal.set(year, month - 1, day, hour, min, sec)
        // target zone: how the packet time is emitted
        val tgtZone = when (mode) {
            GpsGateTimestampMode.UTC_NO_FIX, GpsGateTimestampMode.LOCAL_FIX_UTC ->
                java.util.TimeZone.getTimeZone("UTC")
            GpsGateTimestampMode.UTC_FIX_LOCAL -> java.util.TimeZone.getDefault()
        }
        val out = java.util.Calendar.getInstance(tgtZone)
        out.timeInMillis = cal.timeInMillis
        return out
    }

    // ------------------------------------------------------------------ CRC-64
    // CRC-64/ECMA-182 (aka CRC-64/XZ): poly 0x42F0E1EBA9EA3693, init 0,
    // reflected (lsb-first). 16 lowercase hex chars.
    private const val POLY: Long = 0x42F0E1EBA9EA3693L

    /** CRC-64/ECMA-182 of the UTF-8 bytes of [text]. */
    fun crc64(text: String): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var crc = 0L
        for (b in bytes) {
            crc = crc xor (b.toLong() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 1L) != 0L) (crc ushr 1) xor POLY else crc ushr 1
            }
        }
        return crc
    }

    /** 16 lowercase hex chars of CRC64 over [text]. */
    fun crc64Hex(text: String): String {
        val v = crc64(text)
        return String.format(Locale.US, "%016x", v)
    }

    // ------------------------------------------------------------------ IMEI
    /** Resolve the IMEI for a server rule given the FRCMD sender id. */
    fun resolveImei(server: GpsGateServer, frcmdSender: String): String {
        return when (server.imeiSource) {
            GpsGateImeiSource.EXPLICIT -> server.imeiExplicit.lowercase()
            GpsGateImeiSource.CRC_TEXT -> crc64Hex(server.imeiText)
            GpsGateImeiSource.CRC_FRCMD -> crc64Hex(frcmdSender)
        }
    }

    // ------------------------------------------------------------------ rtt006
    /** Frame: <7-digit-length>rtt006;IMEI;<data> (date/time from [cal]). */
    fun buildRtt006(imei: String, pos: FrcmdPosition, cal: java.util.Calendar): String {
        val date = String.format(Locale.US, "%04d%02d%02d",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
        val time = String.format(Locale.US, "%02d%02d%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND))
        val unix = cal.timeInMillis / 1000L
        val hdop = java.math.BigDecimal.valueOf(3.0 / 8.0)
            .setScale(2, java.math.RoundingMode.UP).stripTrailingZeros().toPlainString()

        val data = "A|${fmt(pos.lat)}|${fmt(pos.lon)}|${fmt(pos.speedKmh)}|${fmt(pos.altitude)}|" +
            "${fmt(pos.course)}|$date|$time|3|$unix," +
            "12|3.0|$hdop|1," +
            "0|250|1|1234|56789|75," +
            "78|1|31.0|1|0|1|," +
            "300|0|0|0|1.0.0,,"

        val body = "rtt006;$imei;$data"
        val len = String.format(Locale.US, "%07d", body.toByteArray(Charsets.UTF_8).size)
        return len + body
    }

    // ------------------------------------------------------------------ MT60
    /** Packet: $MGV002,<imei>,<imei>,S,ddMMyy,HHmmss,A,... (date/time from [cal]). */
    fun buildMt60(imei: String, pos: FrcmdPosition, cal: java.util.Calendar): String {
        val date = String.format(Locale.US, "%02d%02d%02d",
            cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1,
            (cal.get(java.util.Calendar.YEAR) % 100))
        val time = String.format(Locale.US, "%02d%02d%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND))
        val knots = pos.speedKmh / 1.852
        val sb = StringBuilder("\$MGV002,")
            .append(imei).append(',').append(imei).append(",S,")
            .append(date).append(',').append(time).append(",A,")
            .append(nmeaLat(pos.lat)).append(",N,")
            .append(nmeaLon(pos.lon)).append(",E,")
            .append("00,12,00,0.00,")
            .append(String.format(Locale.US, "%.2f", knots)).append(',')
            .append(Math.round(pos.course)).append(',')
            .append(Math.round(pos.altitude)).append(",,")
            .append("250,1,1234,56789,75")
        // 9 empty fields, then "00", battery, alert
        for (i in 0 until 9) sb.append(',')
        sb.append("00,78,Timer;!")
        return sb.toString()
    }

    // ------------------------------------------------------------------ GL200
    /** Packet: +RESP:GTFRI,301201,<imei>,... (date/time from [cal]). */
    fun buildGl200(imei: String, pos: FrcmdPosition, cal: java.util.Calendar): String {
        val utc = String.format(Locale.US, "%02d%02d%02d%02d%02d%02d",
            (cal.get(java.util.Calendar.YEAR) % 100), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
        val rand = Integer.toHexString(ThreadLocalRandom.current().nextInt(65536))
        return "+RESP:GTFRI,301201,$imei,,0,0,1,0," +
            String.format(Locale.US, "%.2f", pos.speedKmh) + "," +
            Math.round(pos.course) + "," +
            (if (pos.altitude == 0.0) "0" else String.format(Locale.US, "%.1f", pos.altitude)) + "," +
            plain(pos.lon) + "," + plain(pos.lat) + "," +
            "$utc,250,1,1234,56789,,78,$utc,$rand$"
    }

    // ------------------------------------------------------------------ build
    /**
     * Build the outbound packet for the given protocol. The timestamp is taken
     * from the $FRCMD date/time fields and re-interpreted per [mode]; when the
     * packet has no usable date/time, "now" (UTC) is used as a fallback.
     */
    fun buildPacket(proto: GpsGateProto, imei: String, pos: FrcmdPosition, mode: GpsGateTimestampMode): ByteArray {
        var cal = resolveTimestamp(pos, mode)
        if (cal == null) {
            cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        }
        val s: String = when (proto) {
            GpsGateProto.RTT006 -> buildRtt006(imei, pos, cal)
            GpsGateProto.MT60 -> buildMt60(imei, pos, cal)
            GpsGateProto.GL200 -> buildGl200(imei, pos, cal)
        }
        return (s + "\r\n").toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ helpers
    private fun fmt(v: Double): String {
        return if (v == Math.floor(v) && !Math.abs(v).isInfinite() && Math.abs(v) < 1e15)
            v.toLong().toString() else java.math.BigDecimal.valueOf(v).toPlainString()
    }

    private fun plain(v: Double) = java.math.BigDecimal.valueOf(v).toPlainString()

    private fun nmeaBase(v: Double, degWidth: Int): String {
        val a = Math.abs(v)
        val d = a.toInt()
        val m = (a - d) * 60.0
        return String.format(Locale.US, "%0${degWidth}d%07.4f", d, m)
    }

    private fun nmeaLat(lat: Double) = nmeaBase(lat, 2)
    private fun nmeaLon(lon: Double) = nmeaBase(lon, 3)
}