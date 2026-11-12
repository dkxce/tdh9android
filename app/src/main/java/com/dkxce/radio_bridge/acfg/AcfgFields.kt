/*
 ==============================================================================
  ACFG field model: GNSS / Site Type / APRS / Relay-Routing / Advanced config
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.5.0

  English:  Kotlin port of the desktop td9_aprs.py field tables (WRITABLE,
            SETTINGS_GROUPS, CHOICE_FIELDS) plus the decode/encode logic, so
            the Android ACFG tab can read and write the TD-H9 settings image
            over the FF00 clone service. Offsets are MEMORY offsets (the image
            starts with an 8-byte identity; memory = image[8:]).
  Russian:  Поля настроек TD-H9 для вкладки ACFG (порт таблиц и логики
            декодирования/кодирования из desktop td9_aprs.py). Адреса —
            смещения в памяти (без 8-байтного identity).
 ==============================================================================
 */

package com.dkxce.radio_bridge.acfg

/** Field kind: how the raw bytes are interpreted. */
enum class FieldKind {
    BYTE,      // 1 byte raw index/value
    YESNO,     // 1 byte: 0=Off 1=On
    U16LE,     // 2 bytes little-endian
    TEXT6,     // 6 bytes zero/0xFF padded ASCII
    TEXT8,     // 8 bytes
    TEXT64,    // 64 bytes
    COORD5,    // fixed lat/lon: u32BE micro-degrees + direction byte at +4
    COORD4,    // fixed alt: i32BE micro-units
}

/** One writable field of the TD-H9 APRS/GNSS configuration block. */
data class AcfgField(
    val key: String,
    val label: String,
    val offset: Int,
    val kind: FieldKind,
    val choices: List<String> = emptyList(),
    val isChoice: Boolean = false,
    val hint: String = "",
) {
    val length: Int get() = when (kind) {
        FieldKind.BYTE, FieldKind.YESNO -> 1
        FieldKind.U16LE -> 2
        FieldKind.TEXT6 -> 6
        FieldKind.TEXT8 -> 8
        FieldKind.TEXT64 -> 64
        FieldKind.COORD5 -> 5
        FieldKind.COORD4 -> 4
    }

    /** Range check helper for base numeric fields (0..255 default). */
    val minValue: Int
    val maxValue: Int

    init {
        var lo = 0; var hi = 255
        when (key) {
            "ssid" -> hi = 15
            "beacon_interval" -> { lo = 0; hi = 65535 }
        }
        minValue = lo; maxValue = hi
    }
}

/** Icon table byte values for the [icon_table] field. */
object IconTables {
    const val PRIMARY: Int = 0x2F      // '/'
    const val SECONDARY: Int = 0x5C    // '\'
}

/** Logical field groups for the ACFG tab (same order as the desktop GUI). */
val ACFG_GROUPS: List<Pair<String, List<AcfgField>>> = listOf(
    "GNSS" to listOf(
        AcfgField("gps_on", "GPS ON/OFF", 0x3066, FieldKind.YESNO, isChoice = true),
        AcfgField("position_format", "Position", 0x3067, FieldKind.BYTE, isChoice = true),
        AcfgField("time_zone", "Time Zone", 0x3068, FieldKind.BYTE, isChoice = true),
        AcfgField("speed_unit", "Speed Unit", 0x3069, FieldKind.BYTE, isChoice = true),
        AcfgField("dist_unit", "Distance Unit", 0x306A, FieldKind.BYTE, isChoice = true),
        AcfgField("alt_unit", "Altitude Unit", 0x306B, FieldKind.BYTE, isChoice = true),
        AcfgField("fixed_lat", "Fixed Latitude", 0x3071, FieldKind.COORD5),
        AcfgField("fixed_lon", "Fixed Longitude", 0x306C, FieldKind.COORD5),
        AcfgField("fixed_alt", "Fixed Altitude", 0x3076, FieldKind.COORD4),
    ),
    "Site Type" to listOf(
        AcfgField("station_type", "Position source", 0x307B, FieldKind.BYTE, isChoice = true),
    ),
    "APRS" to listOf(
        AcfgField("aprs_on", "APRS Switch", 0x307C, FieldKind.YESNO, isChoice = true),
        AcfgField("callsign", "Call Sign", 0x307D, FieldKind.TEXT6),
        AcfgField("ssid", "SSID (0-15)", 0x3083, FieldKind.BYTE),
        AcfgField("icon_table", "SSID Symbol", 0x3084, FieldKind.BYTE, isChoice = true),
        AcfgField("comment", "Custom Information", 0x3095, FieldKind.TEXT64),
        AcfgField("icon_symbol", "Icon Settings", 0x30D6, FieldKind.BYTE, isChoice = true),
        AcfgField("mic_e_start", "MIC-E Start", 0x30D7, FieldKind.YESNO, isChoice = true),
        AcfgField("mic_e_type", "MIC-E Type", 0x30D8, FieldKind.BYTE, isChoice = true),
        AcfgField("route1_name", "Route 1", 0x30D9, FieldKind.TEXT8),
        AcfgField("route1_count", "Route 1 Count (0-9)", 0x30E1, FieldKind.BYTE, isChoice = true),
        AcfgField("route2_name", "Route 2", 0x30E2, FieldKind.TEXT8),
        AcfgField("route2_count", "Route 2 Count (0-9)", 0x30EA, FieldKind.BYTE, isChoice = true),
        AcfgField("report_voltage", "Report Voltage", 0x30EB, FieldKind.YESNO, isChoice = true),
        AcfgField("report_sats", "Report Sats", 0x30EC, FieldKind.YESNO, isChoice = true),
        AcfgField("report_mileage", "Report Mileage", 0x30ED, FieldKind.YESNO, isChoice = true),
        AcfgField("ptt_link", "PTT Linkage", 0x30EE, FieldKind.YESNO, isChoice = true),
        AcfgField("timed_beacon", "Timed Beacon", 0x30EF, FieldKind.YESNO, isChoice = true),
        AcfgField("beacon_interval", "Timing (seconds)", 0x30F0, FieldKind.U16LE),
    ),
    "Relay / Routing" to listOf(
        AcfgField("digi_fwd_channel", "DIGI Forward CH", 0x30F2, FieldKind.BYTE, isChoice = true),
        AcfgField("digi1_enabled", "DIGI1 Enable", 0x30F3, FieldKind.YESNO, isChoice = true),
        AcfgField("digi1_name", "DIGI1 Name", 0x30F4, FieldKind.TEXT8),
        AcfgField("digi2_enabled", "DIGI2 Enable", 0x30FC, FieldKind.YESNO, isChoice = true),
        AcfgField("digi2_name", "DIGI2 Name", 0x30FD, FieldKind.TEXT8),
        AcfgField("forward_wait", "Wait Before Forwarding (s)", 0x3105, FieldKind.BYTE),
        AcfgField("remote_password", "Remote Password", 0x3106, FieldKind.TEXT8),
    ),
    "Advanced Set" to listOf(
        AcfgField("rx_channel", "APRS RX CH", 0x310E, FieldKind.BYTE, isChoice = true),
        AcfgField("tx_channel", "APRS TX CH", 0x310F, FieldKind.BYTE, isChoice = true),
        AcfgField("ptt_priority", "PTT Priority", 0x3110, FieldKind.BYTE, isChoice = true),
        AcfgField("rx_popup", "Beacon Auto Popup", 0x3113, FieldKind.YESNO, isChoice = true),
    ),
)

/** All fields flattened; key -> field (with choices attached). */
val ACFG_FIELDS: Map<String, AcfgField> by lazy {
    ACFG_GROUPS_BUILT.flatMap { it.second }.associateBy { it.key }
}

/** Choice-value lists (same values/casing as desktop CHOICE_FIELDS). */
val ACFG_CHOICES: Map<String, List<String>> = run {
    val m = mutableMapOf<String, List<String>>()
    fun put(key: String, vararg vals: String) { m[key] = listOf(*vals) }
    put("gps_on", "Off", "On")
    put("position_format", "Degrees", "Degrees + minutes", "Degrees + minutes + seconds")
    m["time_zone"] = (-12..12).map { "UTC%+d".format(it) }
    put("speed_unit", "km/h", "knots", "m/s")
    put("dist_unit", "km", "nautical miles", "miles")
    put("alt_unit", "meters", "feet")
    put("station_type", "Fixed coordinates", "GNSS")
    put("aprs_on", "Off", "On")
    put("icon_table", "Primary /", "Alternate \\")
    put("mic_e_start", "Off", "On")
    put("mic_e_type", "Off duty", "En route", "In service", "Returning",
        "Committed", "Special", "Priority", "Emergency")
    m["route1_count"] = (0..9).map { it.toString() }
    m["route2_count"] = (0..9).map { it.toString() }
    put("report_voltage", "Off", "On")
    put("report_sats", "Off", "On")
    put("report_mileage", "Off", "On")
    put("ptt_link", "Off", "On")
    put("timed_beacon", "Off", "On")
    put("digi_fwd_channel", "A", "B", "A + B")
    put("digi1_enabled", "Off", "On")
    put("digi2_enabled", "Off", "On")
    put("rx_channel", "Off", "A", "B")
    put("tx_channel", "A locked", "B locked", "A auto", "B auto", "A + B")
    put("ptt_priority", "Call", "APRS")
    put("rx_popup", "Off", "On")
    m
}

/** Tooltip for one choice field (mirrors desktop CHOICE_HINTS). */
val ACFG_HINTS: Map<String, String> = mapOf(
    "tx_channel" to ("CHA-Lock: APRS TX is hard-bound to upper channel A. " +
        "CHB-Lock: hard-bound to upper channel B. " +
        "CHA-Auto: CHA is primary, the radio auto-switches TX to CHB " +
        "when an APRS request/message arrived on the lower channel. " +
        "CHB-Auto: CHB is primary with automatic packet reply on CHA " +
        "when incoming data traffic was received there. " +
        "CHA+CHB: duplicate / simultaneous sending through both " +
        "receivers - the radio transmits APRS alternately on " +
        "channels A and B frequencies."),
)

/** Assign the choice list (and icon special-case) to each choice field. */
fun buildFields(): List<Pair<String, List<AcfgField>>> {
    val out = mutableListOf<Pair<String, List<AcfgField>>>()
    for ((group, fields) in ACFG_GROUPS) {
        val upd = fields.map { f ->
            when (f.key) {
                "icon_symbol" -> f.copy(isChoice = true, choices = emptyList())
                "icon_table" -> f.copy(isChoice = true, choices = listOf("Primary /", "Alternate \\"))
                "time_zone" -> f.copy(isChoice = true, choices = ACFG_CHOICES["time_zone"]!!)
                else -> if (f.isChoice)
                    f.copy(choices = ACFG_CHOICES[f.key] ?: emptyList())
                else f
            }
        }
        out.add(group to upd)
    }
    return out
}

/** Updated groups with choices attached (use this everywhere in the UI). */
val ACFG_GROUPS_BUILT: List<Pair<String, List<AcfgField>>> by lazy { buildFields() }

// ---------------------------------------------------------------------------
// Decoding (raw bytes -> display string) — mirrors td9_aprs.decode_setting.
// ---------------------------------------------------------------------------

private fun decodeText(raw: ByteArray): String {
    // text fields are padded with 0x00 / 0xFF; cut at the first.
    var end = raw.size
    for ((i, b) in raw.withIndex()) {
        if (b.toInt() == 0 || b.toInt() == 0xFF) { end = i; break }
    }
    return String(raw, 0, end, Charsets.ISO_8859_1)
}

private fun decodeByteChoice(field: AcfgField, raw: ByteArray): String? {
    if (raw.isEmpty()) return null
    val v = raw[0].toInt() and 0xFF
    if (field.choices.isNotEmpty() && v < field.choices.size) return field.choices[v]
    return null
}

/** Decode raw field bytes (exactly [field.length]) into the display string. */
fun decodeAcfgField(field: AcfgField, raw: ByteArray): String {
    val v0 = if (raw.isNotEmpty()) raw[0].toInt() and 0xFF else 0
    return when (field.kind) {
        FieldKind.TEXT6, FieldKind.TEXT8, FieldKind.TEXT64 -> decodeText(raw)
        FieldKind.U16LE -> if (raw.size >= 2)
                           (v0 or ((raw[1].toInt() and 0xFF) shl 8)).toString()
                           else "0"
        FieldKind.COORD5 -> decodeCoord5(raw)
        FieldKind.COORD4 -> decodeCoord4(raw)
        FieldKind.YESNO -> if (v0 == 1) "On" else "Off"
        FieldKind.BYTE -> when (field.key) {
            // icon_table stores 0x2F/0x5C raw (not an index)
            "icon_table" -> when (v0) {
                IconTables.PRIMARY -> "Primary /"
                IconTables.SECONDARY -> "Alternate \\"
                else -> v0.toString()
            }
            "icon_symbol" -> iconLabel(v0)
            else -> decodeByteChoice(field, raw) ?: v0.toString()
        }
    }
}

private fun decodeCoord5(raw: ByteArray): String {
    if (raw.size < 5) return ""
    val allff = raw.take(4).all { it.toInt() == 0xFF }
    val all0 = raw.take(4).all { it.toInt() == 0 }
    if (all0 || allff) return ""
    val rawVal = ((raw[0].toInt() and 0xFF) shl 24) or
            ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or
            (raw[3].toInt() and 0xFF)
    val d = raw[4].toInt().toChar()
    val suffix = if (d in arrayOf('N', 'S', 'E', 'W')) d.toString() else ""
    val v = rawVal / 1_000_000.0
    return String.format(java.util.Locale.US, "%.6f%s", v, suffix)
}

private fun decodeCoord4(raw: ByteArray): String {
    if (raw.size < 4) return ""
    val allff = raw.all { it.toInt() == 0xFF }
    val all0 = raw.all { it.toInt() == 0 }
    if (all0 || allff) return ""
    val rawVal = ((raw[0].toInt() and 0xFF) shl 24) or
            ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or
            (raw[3].toInt() and 0xFF)
    val signed = if (rawVal and 0x80000000.toInt() != 0)
        rawVal - (1 shl 32) else rawVal
    return String.format(java.util.Locale.US, "%.6f", signed / 1_000_000.0)
}

/** "27: /< - Motorcycle" label for an icon index. */
fun iconLabel(index: Int, table: String = "primary"): String {
    val chars = APRS_SYMBOL_CHARS
    if (index !in 0..93) return index.toString()
    val ch = chars[index]
    val prefix = if (table == "secondary") "\\" else "/"
    val name = if (table == "secondary") SECONDARY_ICON_NAMES[index]
               else PRIMARY_ICON_NAMES[index]
    val base = "$index: $prefix$ch"
    return if (name.isNotEmpty()) "$base - $name" else base
}

/** Table of symbol characters for indices 0..93 (same order as desktop). */
val APRS_SYMBOL_CHARS: List<Char> = listOf(
    '!','"','#','$','%','&','\'','(',')','*','+',',','-','.','/',
    '0','1','2','3','4','5','6','7','8','9',':',';','<','=','>','?','@',
    'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q',
    'R','S','T','U','V','W','X','Y','Z','[','\\',']','^','_','`',
    'a','b','c','d','e','f','g','h','i','j','k','l','m','n','o','p','q',
    'r','s','t','u','v','w','x','y','z','{','|','}','~',
).also { check(it.size == 94) }

/** Primary icon names (index order). Mirrors td9_aprs._PRIMARY_ICON_NAMES. */
val PRIMARY_ICON_NAMES: List<String> = listOf(
    "Police, Sheriff", "", "Digi", "Phone", "DX Cluster", "HF Gateway",
    "Small Aircraft", "Mobile Satellite Ground Station", "Wheelchair",
    "Snowmobile", "Red Cross", "Boy Scouts", "House QTH (VHF)", "X",
    "Red Dot", "0 Circle", "1 Circle", "2 Circle", "3 Circle", "4 Circle",
    "5 Circle", "6 Circle", "7 Circle", "8 Circle", "9 Circle", "Fire",
    "Campground", "Motorcycle", "Railroad Engine", "Car", "File Server",
    "Hurricane Future Prediction", "Aid Station", "BBS or PBBS", "Canoe",
    "", "Eyeball", "Farm Vehicle (Tractor)", "Grid Square (6-char)",
    "Hotel", "TCP/IP on air network station", "", "School", "PC user",
    "MacAPRS", "NTS Station", "Balloon", "Police", "TBD",
    "Recreational Vehicle", "Space Shuttle", "SSTV", "Bus", "Amateur TV",
    "NWS Site", "Helicopter", "Yacht", "WinAPRS", "Jogger",
    "Triangle (DF)", "Mail/Post Office", "Large Aircraft",
    "Weather Station", "Dish Antenna", "Ambulance", "Bicycle",
    "Incident Command Post", "Fire Department", "Horse (equestrian)",
    "Fire Truck", "Glider", "Hospital", "IOTA", "Jeep", "Truck",
    "Laptop", "Mic-E Repeater", "Node", "Emergency Ops Center",
    "Rover (puppy dog)", "Grid Square >128m", "Repeater", "Ship",
    "Truck Stop", "Truck (18-wheeler)", "Van", "Water Station",
    "X-APRS", "Yagi at QTH", "TBD", "", "", "", "",
).also { check(it.size == 94) }

/** Secondary icon names (index order). */
val SECONDARY_ICON_NAMES: List<String> = listOf(
    "Emergency", "", "Digi (green star)", "Bank or ATM", "Power Plant",
    "IGate (I=In, R=RX, T=1hopTX, 2=2hopTX)", "Crash & incident sites",
    "Cloudy", "Firenet MEO", "Snow", "Church", "Girl Scouts",
    "House (H=HF, O=Op Present)", "Ambiguous (Question Mark)",
    "Waypoint Destination", "Circle (E/I/W = IRLP/EchoLink/WIRES)", "",
    "", "", "", "", "", "", "802.11 or other network node", "Gas Station",
    "Hail", "Park/Picnic Area", "Advisory (one WX flag)",
    "APRStt Touchtone (DTMF)", "Cars & Vehicles", "Information Kiosk",
    "Hurricane/Tropical Storm", "Box: DTMF, RFID, XO", "Blowing Snow",
    "Coast Guard", "Drizzle", "Smoke", "Freezing Rain", "Snow Shower",
    "Haze", "Rain Shower", "Lightning", "Kenwood HT (w)", "Lighthouse",
    "MARS (A=Army, N=Navy, F=AF)", "Navigation Buoy", "Rocket",
    "Parking", "Earthquake", "Restaurant", "Satellite", "Thunderstorm",
    "Sunny", "VORTAC Nav Aid", "NWS Site", "Pharmacy Rx",
    "Radios and devices", "", "Wall Cloud", "", "", "Aircraft (Shows Heading)",
    "WX Station with Digi", "Rain", "ARRL, ARES, WinLINK, Dstar, LoRa, etc",
    "Blowing Dust/Sand", "CD triangle RACES/SATERN", "DX Spot", "Sleet",
    "Funnel Cloud", "Gale Flags", "Store or Hamfest",
    "BOX or points of Interest", "Work Zone (steam shovel)",
    "Special Vehicle SUV, ATV, 4x4", "Area Symbols", "Value Sign",
    "Triangle", "Small Circle", "Partly Cloudy", "", "Restrooms",
    "Ship/Boat (top view)", "Tornado", "Truck", "Van",
    "Flooding (Avalanches)", "Wreck or Obstruction", "Skywarn",
    "Overlayed Shelter", "Fog", "", "", "",
).also { check(it.size == 94) }

// ---------------------------------------------------------------------------
// Encoding (display string -> raw bytes) — mirrors td9_aprs.encode_setting.
// ---------------------------------------------------------------------------

private fun normalizeLabel(s: String): String =
    s.lowercase().trim().replace(Regex("\\s+"), " ")

/** Build the label->index map for a choice field. */
private fun choiceIndexByName(field: AcfgField): Map<String, Int> {
    val m = mutableMapOf<String, Int>()
    field.choices.forEachIndexed { i, label -> m[normalizeLabel(label)] = i }
    // icon_table: labels map to raw bytes 0x2F / 0x5C (not 0/1)
    if (field.key == "icon_table") {
        m.clear()
        m["primary /"] = IconTables.PRIMARY
        m["alternate \\"] = IconTables.SECONDARY
    }
    if (field.key == "ptt_priority") {
        m.clear()
        m["call"] = 0
        m["aprs"] = 1
    }
    // time_zone: "UTC+3" etc.
    if (field.key == "time_zone") {
        m.clear()
        for (i in 0..24) m["utc%+d".format(i - 12)] = i
    }
    return m
}

/**
 * Encode a display string into the raw field bytes.
 * @throws IllegalArgumentException on a bad value.
 */
fun encodeAcfgField(field: AcfgField, value: String): ByteArray {
    val v = value.trim()
    if (field.kind == FieldKind.TEXT6 || field.kind == FieldKind.TEXT8 ||
        field.kind == FieldKind.TEXT64) {
        var enc: ByteArray = v.toByteArray(Charsets.ISO_8859_1)
        if (enc.size > field.length) enc = enc.copyOf(field.length)
        return enc + ByteArray(field.length - enc.size)
    }
    if (field.key == "icon_symbol") {
        // accept "27", "0x1B 27: /<", "27: /<  Motorcycle"
        val m = Regex("""^\s*(?:0x[0-9A-Fa-f]+\s+)?(\d+)\s*:""").find(v)
        val idx = if (m != null) m.groupValues[1].toInt() else v.toInt()
        if (idx !in 0..255) throw IllegalArgumentException("icon_symbol out of range")
        return byteArrayOf((idx and 0xFF).toByte())
    }
    if (field.kind == FieldKind.COORD5) {
        var s = v.uppercase().trim()
        var suffix = ""
        if (s.isNotEmpty() && s.last() in charArrayOf('N', 'S', 'E', 'W')) {
            suffix = s.last().toString(); s = s.dropLast(1).trim()
        }
        val f = s.toDoubleOrNull()
            ?: throw IllegalArgumentException("bad coordinate: $value")
        if (f < -180.0 || f > 180.0) throw IllegalArgumentException("coordinate range: $value")
        val rawVal = Math.round(f * 1_000_000.0).toInt()
        val b = ByteArray(4)
        b[0] = (rawVal ushr 24).toByte(); b[1] = (rawVal ushr 16).toByte()
        b[2] = (rawVal ushr 8).toByte(); b[3] = rawVal.toByte()
        return b + suffix.toByteArray(Charsets.US_ASCII)
    }
    if (field.kind == FieldKind.COORD4) {
        val f = v.toDoubleOrNull() ?: throw IllegalArgumentException("bad altitude: $value")
        val rawVal = Math.round(f * 1_000_000.0).toInt()
        val b = ByteArray(4)
        b[0] = (rawVal ushr 24).toByte(); b[1] = (rawVal ushr 16).toByte()
        b[2] = (rawVal ushr 8).toByte(); b[3] = rawVal.toByte()
        return b
    }
    if (field.kind == FieldKind.U16LE) {
        val n = v.toIntOrNull() ?: throw IllegalArgumentException("bad number: $value")
        if (n < field.minValue || n > field.maxValue)
            throw IllegalArgumentException("$value out of range ${field.minValue}..${field.maxValue}")
        return byteArrayOf((n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
    }
    // yesno / choice / byte
    if (field.isChoice && field.key != "icon_symbol") {
        val lookup = choiceIndexByName(field)
        val sv = normalizeLabel(v)
        val direct = lookup[sv]
        if (direct != null) return byteArrayOf(direct.toByte())
        val asInt = v.toIntOrNull()
        if (asInt != null) {
            if (field.key == "icon_table") {
                if (asInt == IconTables.PRIMARY || asInt == IconTables.SECONDARY)
                    return byteArrayOf(asInt.toByte())
            } else {
                val keys = lookup.values.toSortedSet()
                if (asInt in keys) return byteArrayOf(asInt.toByte())
            }
        }
        throw IllegalArgumentException(
            "unknown value '$value'; pick one of ${field.choices.joinToString(", ")}")
    }
    // yesno
    if (field.kind == FieldKind.YESNO) {
        val on = v.lowercase() in setOf("1", "on", "yes")
        return byteArrayOf(if (on) 1 else 0)
    }
    // plain byte/number
    val n = v.toIntOrNull() ?: throw IllegalArgumentException("bad number: $value")
    if (n < field.minValue || n > field.maxValue)
        throw IllegalArgumentException("$value out of range ${field.minValue}..${field.maxValue}")
    return byteArrayOf(n.toByte())
}