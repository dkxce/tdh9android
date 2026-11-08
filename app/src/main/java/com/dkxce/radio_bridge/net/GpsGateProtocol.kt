/*
 ==============================================================================
  GPSGate protocol helpers: $FRCMD parsing, CRC-64/ECMA, tracker packets
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.2.0

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
        return FrcmdPosition(
            senderId = sender,
            lat = if (ns == "S") -lat else lat,
            lon = if (ew == "W") -lon else lon,
            speedKmh = speed,
            course = course,
            altitude = alt,
        )
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
    /** Frame: <7-digit-length>rtt006;IMEI;<data> */
    fun buildRtt006(imei: String, pos: FrcmdPosition, unix: Long): String {
        val cl = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cl.timeInMillis = unix * 1000L
        val date = String.format(Locale.US, "%04d%02d%02d",
            cl.get(java.util.Calendar.YEAR), cl.get(java.util.Calendar.MONTH) + 1,
            cl.get(java.util.Calendar.DAY_OF_MONTH))
        val time = String.format(Locale.US, "%02d%02d%02d",
            cl.get(java.util.Calendar.HOUR_OF_DAY), cl.get(java.util.Calendar.MINUTE),
            cl.get(java.util.Calendar.SECOND))
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
    /** Packet: $MGV002,<imei>,<imei>,S,ddMMyy,HHmmss,A,<latNMEA>,N,<lonNMEA>,E,00,12,00,hdop,speedKnots,heading,alt,,mcc,mnc,lac,cell,gsm,<x9 empty>,00,batt,Timer;! */
    fun buildMt60(imei: String, pos: FrcmdPosition): String {
        val cl = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val date = String.format(Locale.US, "%02d%02d%02d",
            cl.get(java.util.Calendar.DAY_OF_MONTH), cl.get(java.util.Calendar.MONTH) + 1,
            (cl.get(java.util.Calendar.YEAR) % 100))
        val time = String.format(Locale.US, "%02d%02d%02d",
            cl.get(java.util.Calendar.HOUR_OF_DAY), cl.get(java.util.Calendar.MINUTE),
            cl.get(java.util.Calendar.SECOND))
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
    /** Packet: +RESP:GTFRI,301201,<imei>,,0,0,1,acc,speed,heading,alt,lon,lat,yyMMddHHmmss,mcc,mnc,lac,cell,,batt,yyMMddHHmmss,rand$ */
    fun buildGl200(imei: String, pos: FrcmdPosition): String {
        val cl = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val utc = String.format(Locale.US, "%02d%02d%02d%02d%02d%02d",
            (cl.get(java.util.Calendar.YEAR) % 100), cl.get(java.util.Calendar.MONTH) + 1,
            cl.get(java.util.Calendar.DAY_OF_MONTH), cl.get(java.util.Calendar.HOUR_OF_DAY),
            cl.get(java.util.Calendar.MINUTE), cl.get(java.util.Calendar.SECOND))
        val rand = Integer.toHexString(ThreadLocalRandom.current().nextInt(65536))
        return "+RESP:GTFRI,301201,$imei,,0,0,1,0," +
            String.format(Locale.US, "%.2f", pos.speedKmh) + "," +
            Math.round(pos.course) + "," +
            (if (pos.altitude == 0.0) "0" else String.format(Locale.US, "%.1f", pos.altitude)) + "," +
            plain(pos.lon) + "," + plain(pos.lat) + "," +
            "$utc,250,1,1234,56789,,78,$utc,$rand$"
    }

    // ------------------------------------------------------------------ build
    /** Build the outbound packet for the given protocol. */
    fun buildPacket(proto: GpsGateProto, imei: String, pos: FrcmdPosition, unix: Long): ByteArray {
        val s: String = when (proto) {
            GpsGateProto.RTT006 -> buildRtt006(imei, pos, unix)
            GpsGateProto.MT60 -> buildMt60(imei, pos)
            GpsGateProto.GL200 -> buildGl200(imei, pos)
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