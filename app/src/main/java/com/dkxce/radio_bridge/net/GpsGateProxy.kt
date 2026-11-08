/*
 ==============================================================================
  GPSGate proxy configuration: local Franson GPSGate HTTP listener + forwarders
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.2.0

  English:  Configuration and stats for the GPSGate proxy. The proxy runs a
            local HTTP server that speaks the Franson GPSGate protocol
            (GET /?cmd=$FRCMD,...) and forwards each received position to one
            or more remote servers, encoding it as rtt006 / MT60 / GL200.

  Russian:  Конфигурация и статистика GPSGate-прокси. Прокси запускает
            локальный HTTP-сервер протокола Franson GPSGate (GET /?cmd=$FRCMD,...)
            и пересылает принятые позиции на один или несколько серверов,
            кодируя их как rtt006 / MT60 / GL200.
 ==============================================================================
 */

package com.dkxce.radio_bridge.net

/** Supported tracker protocol for the remote (outbound) servers. */
enum class GpsGateProto { RTT006, MT60, GL200 }

/** How the device IMEI is derived for a server. */
enum class GpsGateImeiSource {
    EXPLICIT,      // fixed 16-char lowercase hex string entered by the user
    CRC_TEXT,      // CRC-64/ECMA over a custom text string
    CRC_FRCMD,     // CRC-64/ECMA over the $FRCMD sender id (first field after cmd)
}

/** One outbound server (host:port + protocol + IMEI rule). */
data class GpsGateServer(
    val enabled: Boolean = false,
    val host: String = "livegpstracks.ru",
    val port: Int = 3349,
    val proto: GpsGateProto = GpsGateProto.RTT006,
    val imeiSource: GpsGateImeiSource = GpsGateImeiSource.CRC_FRCMD,
    val imeiExplicit: String = "",   // 16 lowercase hex chars when EXPLICIT
    val imeiText: String = "",       // arbitrary text when CRC_TEXT
)

/** Settings of the GPSGate proxy. */
data class GpsGateProxyConfig(
    val enabled: Boolean = false,        // master switch (UI)
    val listenPort: Int = 12014,         // local HTTP listener port
    val servers: List<GpsGateServer> = listOf(
        GpsGateServer(enabled = true, host = "livegpstracks.ru", port = 3349,
            proto = GpsGateProto.RTT006, imeiSource = GpsGateImeiSource.CRC_FRCMD),
    ),
)

/** Live stats, pushed to the UI. */
data class GpsGateStats(
    val running: Boolean = false,
    val rxPackets: Long = 0,       // $FRCMD packets received from the GPSGate client
    val txPackets: Long = 0,       // packets successfully written to remote servers
    val failedPackets: Long = 0,   // packets that failed to reach any server
    val packetsLog: List<String> = emptyList(),  // last 20 (reversed: newest first)
)