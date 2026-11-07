/*
 ==============================================================================
  App settings + live UI state (SharedPreferences-backed)
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  Process-wide singleton holding the app configuration (server mode,
            port, device history) and the UI state (devices, server status,
            client list, log). Both the UI and the BridgeService observe the
            same instance.
  Russian:  Настройки приложения и состояние интерфейса (единый синглтон).
 ==============================================================================
 */

package com.dkxce.radio_bridge.app

import android.content.Context
import android.content.SharedPreferences
import androidx.documentfile.provider.DocumentFile
import com.dkxce.radio_bridge.igate.IGateConfig
import com.dkxce.radio_bridge.net.ProxyConfig
import com.dkxce.radio_bridge.net.ProxySource
import com.dkxce.radio_bridge.net.ProxyStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which protocol server to run. This is a LOCAL TCP server (the radio is a
 * KISS TNC); there is no outbound IGate client, so no APRS-IS login settings. */
enum class ServerMode(val label: String) {
    KISS("Binary KISS"),
    AGW("AGW (AGWPE)"),
    APRS_IS("APRS-IS"),
}

/** Local bridge server configuration. */
data class BridgeConfig(
    val serverMode: ServerMode = ServerMode.KISS,
    val port: Int = 8023,
)

/** Immutable UI state surfaced to Composables. */
data class UiState(
    val scanning: Boolean = false,
    val devices: List<BleDeviceUi> = emptyList(),
    val history: List<BleDeviceUi> = emptyList(),
    val connectedAddress: String? = null,
    val connectedName: String = "",
    val serviceSummary: List<String> = emptyList(),
    val serverRunning: Boolean = false,
    val serverMode: ServerMode = ServerMode.KISS,
    val port: Int = 8023,
    val clients: Int = 0,
    val clientsList: List<String> = emptyList(),
    val igateConfig: IGateConfig = IGateConfig(),
    val igateRunning: Boolean = false,
    val proxyConfig: ProxyConfig = ProxyConfig(),
    val proxyRunning: Boolean = false,
    val proxyStats: ProxyStats = ProxyStats(),
    val log: List<String> = emptyList(),
)

data class BleDeviceUi(val address: String, val name: String = "", val rssi: Int = 0)

/**
 * Single source of truth for settings and UI state. Holds SharedPreferences
 * wiring and exposes StateFlows that the Compose UI observes.
 *
 * This is a process-wide singleton: both the UI (via MainViewModel) and the
 * BridgeService must observe/mutate the SAME instance, otherwise UI events
 * (discovered devices, logs, server running state) never reach the screen.
 */
class AppModel private constructor(context: Context) {

    private val ctx = context.applicationContext

    companion object {
        @Volatile
        private var instance: AppModel? = null

        @Synchronized
        fun getInstance(context: Context): AppModel {
            return instance ?: AppModel(context.applicationContext)
                .also { instance = it }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("radio_bridge", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val isConfigured: Boolean

    init {
        isConfigured = true
    }

    fun saveConfig(cfg: BridgeConfig) {
        prefs.edit()
            .putString("server_mode", cfg.serverMode.name)
            .putInt("port", cfg.port)
            .apply()
        _ui.value = _ui.value.copy(
            serverMode = cfg.serverMode, port = cfg.port,
        )
    }

    fun loadConfig(): BridgeConfig = BridgeConfig(
        serverMode = ServerMode.valueOf(prefs.getString("server_mode", ServerMode.KISS.name)!!),
        port = prefs.getInt("port", 8023),
    )

    // --- IGate settings (persisted, mirrors Python igate.py) ---

    fun saveIGateConfig(cfg: IGateConfig) {
        prefs.edit()
            .putString("igate_client", cfg.igateClient)
            .putString("igate_name", cfg.igateName)
            .putInt("igate_passcode", cfg.passcode)
            .putString("igate_server", cfg.server)
            .putInt("igate_port", cfg.port)
            .putString("igate_filter", cfg.filter)
            .putBoolean("igate_check_tcpip", cfg.checkTcpip)
            .putBoolean("igate_check_no_qar", cfg.checkNoQar)
            .putBoolean("igate_check_no_qao", cfg.checkNoQao)
            .putBoolean("igate_enabled", cfg.enabled)
            .apply()
        _ui.value = _ui.value.copy(igateConfig = cfg)
    }

    fun loadIGateConfig(): IGateConfig {
        val cfg = IGateConfig(
            igateClient = prefs.getString("igate_client", "NOCALL-10")!!,
            igateName = prefs.getString("igate_name", "NOCALL-1")!!,
            passcode = prefs.getInt("igate_passcode", 0),
            server = prefs.getString("igate_server", "rotate.aprs2.net")!!,
            port = prefs.getInt("igate_port", 14580),
            filter = prefs.getString("igate_filter", "")!!,
            checkTcpip = prefs.getBoolean("igate_check_tcpip", true),
            checkNoQar = prefs.getBoolean("igate_check_no_qar", true),
            checkNoQao = prefs.getBoolean("igate_check_no_qao", true),
            enabled = prefs.getBoolean("igate_enabled", false),
        )
        _ui.value = _ui.value.copy(igateConfig = cfg)
        return cfg
    }

    fun setIGateRunning(running: Boolean) { _ui.value = _ui.value.copy(igateRunning = running) }

    // --- Proxy (APRS-IS bridge) settings + state ---

    fun saveProxyConfig(cfg: ProxyConfig) {
        prefs.edit()
            .putString("proxy_callsign", cfg.callsign)
            .putInt("proxy_passcode", cfg.passcode)
            .putString("proxy_server", cfg.server)
            .putInt("proxy_server_port", cfg.serverPort)
            .putInt("proxy_listen_port", cfg.listenPort)
            .putString("proxy_filter", cfg.filter)
            .putString("proxy_igate_name", cfg.igateName)
            .putString("proxy_source", cfg.source.name)
            .putBoolean("proxy_radio_to_clients", cfg.radioToClients)
            .putBoolean("proxy_radio_to_internet", cfg.radioToInternet)
            .putBoolean("proxy_internet_to_radio", cfg.internetToRadio)
            .putBoolean("proxy_clients_to_radio", cfg.clientsToRadio)
            .putBoolean("proxy_clients_to_internet", cfg.clientsToInternet)
            .putBoolean("proxy_apply_qar", cfg.applyQar)
            .putBoolean("proxy_radio_to_clients_cache", cfg.radioToClientsCache)
            .putBoolean("proxy_internet_to_clients_cache", cfg.internetToClientsCache)
            .putLong("proxy_ping_seconds", cfg.pingSeconds)
            .putLong("proxy_cache_seconds", cfg.cacheSeconds)
            .putLong("proxy_idle_timeout_seconds", cfg.idleTimeoutSeconds)
            .putBoolean("proxy_ais_enabled", cfg.aisEnabled)
            .putInt("proxy_ais_port", cfg.aisPort)
            .putBoolean("proxy_radio_to_ais", cfg.radioToAis)
            .putBoolean("proxy_internet_to_ais", cfg.internetToAis)
            .putBoolean("proxy_radio_to_ais_cache", cfg.radioToAisCache)
            .putBoolean("proxy_internet_to_ais_cache", cfg.internetToAisCache)
            .putBoolean("proxy_enabled", cfg.enabled)
            .apply()
        _ui.value = _ui.value.copy(proxyConfig = cfg)
    }

    fun loadProxyConfig(): ProxyConfig {
        val cfg = ProxyConfig(
            callsign = prefs.getString("proxy_callsign", "NOCALL-10")!!,
            passcode = prefs.getInt("proxy_passcode", 0),
            server = prefs.getString("proxy_server", "rotate.aprs2.net")!!,
            serverPort = if (prefs.contains("proxy_server_port")) prefs.getInt("proxy_server_port", 14580)
                          else prefs.getInt("proxy_port", 14580),
            listenPort = if (prefs.contains("proxy_listen_port")) prefs.getInt("proxy_listen_port", 14580)
                         else prefs.getInt("proxy_port", 14580),
            filter = prefs.getString("proxy_filter", "")!!,
            igateName = prefs.getString("proxy_igate_name", "NOCALL-1")!!,
            source = runCatching {
                ProxySource.valueOf(prefs.getString("proxy_source", ProxySource.FIRST_CLIENT.name)!!)
            }.getOrDefault(ProxySource.FIRST_CLIENT),
            radioToClients = prefs.getBoolean("proxy_radio_to_clients", true),
            radioToInternet = prefs.getBoolean("proxy_radio_to_internet", false),
            internetToRadio = prefs.getBoolean("proxy_internet_to_radio", false),
            clientsToRadio = prefs.getBoolean("proxy_clients_to_radio", true),
            clientsToInternet = prefs.getBoolean("proxy_clients_to_internet", true),
            applyQar = prefs.getBoolean("proxy_apply_qar", true),
            radioToClientsCache = prefs.getBoolean("proxy_radio_to_clients_cache", true),
            internetToClientsCache = prefs.getBoolean("proxy_internet_to_clients_cache", false),
            pingSeconds = prefs.getLong("proxy_ping_seconds", 90),
            cacheSeconds = prefs.getLong("proxy_cache_seconds", 90),
            idleTimeoutSeconds = prefs.getLong("proxy_idle_timeout_seconds", 300),
            aisEnabled = prefs.getBoolean("proxy_ais_enabled", false),
            aisPort = prefs.getInt("proxy_ais_port", 10110),
            radioToAis = prefs.getBoolean("proxy_radio_to_ais", true),
            internetToAis = prefs.getBoolean("proxy_internet_to_ais", false),
            radioToAisCache = prefs.getBoolean("proxy_radio_to_ais_cache", true),
            internetToAisCache = prefs.getBoolean("proxy_internet_to_ais_cache", false),
            enabled = prefs.getBoolean("proxy_enabled", false),
        )
        _ui.value = _ui.value.copy(proxyConfig = cfg)
        return cfg
    }

    fun setProxyRunning(running: Boolean) { _ui.value = _ui.value.copy(proxyRunning = running) }
    fun setProxyStats(stats: ProxyStats) { _ui.value = _ui.value.copy(proxyStats = stats) }
    fun resetProxyStats() { _ui.value = _ui.value.copy(proxyStats = ProxyStats()) }

    // --- UI state mutators (called from ViewModel/service) ---

    fun setScanning(v: Boolean) { _ui.value = _ui.value.copy(scanning = v) }
    fun addDevice(d: BleDeviceUi) {
        if (_ui.value.devices.any { it.address == d.address }) return
        _ui.value = _ui.value.copy(devices = _ui.value.devices + d)
    }
    fun clearDevices() { _ui.value = _ui.value.copy(devices = emptyList()) }
    fun recordConnectedDevice(d: BleDeviceUi) {
        val cur = _ui.value.history.filterNot { it.address == d.address }
        val next = (listOf(d) + cur).take(10)
        // persist as JSON-serialized address list
        prefs.edit()
            .putString("device_history", (listOf(d) + cur).take(10)
                .joinToString(";") { it.address + "|" + it.name })
            .apply()
        _ui.value = _ui.value.copy(history = next)
    }
    fun loadHistory() {
        val raw = prefs.getString("device_history", "") ?: ""
        val list = raw.split(";").filter { it.isNotEmpty() }.mapNotNull { part ->
            val idx = part.indexOf('|')
            if (idx < 0) null
            else BleDeviceUi(part.substring(0, idx), part.substring(idx + 1))
        }
        _ui.value = _ui.value.copy(history = list)
    }
    fun setConnected(addr: String?, name: String) {
        _ui.value = _ui.value.copy(connectedAddress = addr, connectedName = name)
    }
    fun setServices(summary: List<String>) { _ui.value = _ui.value.copy(serviceSummary = summary) }
    fun setServerRunning(running: Boolean) { _ui.value = _ui.value.copy(serverRunning = running) }
    fun setClients(n: Int) { _ui.value = _ui.value.copy(clients = n) }
    fun setClientsList(list: List<String>) { _ui.value = _ui.value.copy(clientsList = list) }

    fun appendLog(text: String) {
        val cur = _ui.value.log
        val stamp = java.time.LocalTime.now().withNano(0).toString() // HH:mm:ss
        val line = "$stamp $text"
        val next = (cur + line).takeLast(200)
        _ui.value = _ui.value.copy(log = next)
    }

    fun clearLog() { _ui.value = _ui.value.copy(log = emptyList()) }

    // ---------------------------------------------------------------- export/import
    // Export all app settings (Server / IGate / Proxy) to a JSON file in filesDir.
    // Import reads that file back and rewrites SharedPreferences + UI state.

    @Throws(java.io.IOException::class)
    fun exportSettings(file: java.io.File) {
        val o = org.json.JSONObject()
        val bridge = _ui.value.let { u ->
            org.json.JSONObject()
                .put("serverMode", u.serverMode.name)
                .put("port", u.port)
        }
        o.put("bridge", bridge)
        o.put("igate", igateToJson(_ui.value.igateConfig))
        o.put("proxy", proxyToJson(_ui.value.proxyConfig))
        file.writeText(o.toString(2))
    }

    @Throws(java.io.IOException::class)
    fun importSettings(file: java.io.File) {
        val o = org.json.JSONObject(file.readText())
        // bridge (server)
        val b = o.optJSONObject("bridge")
        if (b != null) {
            val mode = runCatching { ServerMode.valueOf(b.optString("serverMode", ServerMode.KISS.name)) }
                .getOrDefault(ServerMode.KISS)
            saveConfig(BridgeConfig(serverMode = mode, port = b.optInt("port", 8023)))
        }
        // igate
        val i = o.optJSONObject("igate")
        if (i != null) saveIGateConfig(igateFromJson(i, _ui.value.igateConfig))
        // proxy
        val p = o.optJSONObject("proxy")
        if (p != null) saveProxyConfig(proxyFromJson(p, _ui.value.proxyConfig))
    }

    /** Build the settings JSON string (used by both file and SAF exports). */
    private fun settingsJson(): String {
        val o = org.json.JSONObject()
        val bridge = _ui.value.let { u ->
            org.json.JSONObject()
                .put("serverMode", u.serverMode.name)
                .put("port", u.port)
        }
        o.put("bridge", bridge)
        o.put("igate", igateToJson(_ui.value.igateConfig))
        o.put("proxy", proxyToJson(_ui.value.proxyConfig))
        return o.toString(2)
    }

    private fun parseSettingsJson(text: String) {
        val o = org.json.JSONObject(text)
        val b = o.optJSONObject("bridge")
        if (b != null) {
            val mode = runCatching { ServerMode.valueOf(b.optString("serverMode", ServerMode.KISS.name)) }
                .getOrDefault(ServerMode.KISS)
            saveConfig(BridgeConfig(serverMode = mode, port = b.optInt("port", 8023)))
        }
        val i = o.optJSONObject("igate")
        if (i != null) saveIGateConfig(igateFromJson(i, _ui.value.igateConfig))
        val p = o.optJSONObject("proxy")
        if (p != null) saveProxyConfig(proxyFromJson(p, _ui.value.proxyConfig))
    }

    // Export settings as settings.json inside a SAF folder Uri.
    @Throws(java.io.IOException::class)
    fun exportSettingsToFolder(folderUriString: String) {
        val resolver = ctx.contentResolver
        val folder = DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(folderUriString))
            ?: throw java.io.IOException("fromTreeUri failed")
        val name = "settings.json"
        // reuse existing file if any, otherwise create a new one
        val file = folder.findFile(name)
            ?: folder.createFile("application/json", name)
            ?: throw java.io.IOException("createFile failed")
        resolver.openOutputStream(file.uri)?.use { it.write(settingsJson().toByteArray(Charsets.UTF_8)) }
            ?: throw java.io.IOException("openOutputStream failed")
    }

    // Import settings.json from a SAF folder Uri.
    @Throws(java.io.IOException::class)
    fun importSettingsFromFolder(folderUriString: String) {
        val resolver = ctx.contentResolver
        val folder = DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(folderUriString))
            ?: throw java.io.IOException("fromTreeUri failed")
        val file = folder.findFile("settings.json")
            ?: throw java.io.IOException("settings.json not found in folder")
        val text = resolver.openInputStream(file.uri)?.bufferedReader()?.readText()
            ?: throw java.io.IOException("openInputStream failed")
        parseSettingsJson(text)
    }

    private fun igateToJson(c: IGateConfig): org.json.JSONObject = org.json.JSONObject()
        .put("igateClient", c.igateClient)
        .put("igateName", c.igateName)
        .put("passcode", c.passcode)
        .put("server", c.server)
        .put("port", c.port)
        .put("filter", c.filter)
        .put("checkTcpip", c.checkTcpip)
        .put("checkNoQar", c.checkNoQar)
        .put("checkNoQao", c.checkNoQao)
        .put("enabled", c.enabled)

    private fun igateFromJson(j: org.json.JSONObject, d: IGateConfig): IGateConfig = IGateConfig(
        igateClient = j.optString("igateClient", d.igateClient),
        igateName = j.optString("igateName", d.igateName),
        passcode = j.optInt("passcode", d.passcode),
        server = j.optString("server", d.server),
        port = j.optInt("port", d.port),
        filter = j.optString("filter", d.filter),
        checkTcpip = j.optBoolean("checkTcpip", d.checkTcpip),
        checkNoQar = j.optBoolean("checkNoQar", d.checkNoQar),
        checkNoQao = j.optBoolean("checkNoQao", d.checkNoQao),
        enabled = j.optBoolean("enabled", d.enabled),
    )

    private fun proxyToJson(c: ProxyConfig): org.json.JSONObject = org.json.JSONObject()
        .put("enabled", c.enabled)
        .put("callsign", c.callsign)
        .put("passcode", c.passcode)
        .put("server", c.server)
        .put("serverPort", c.serverPort)
        .put("listenPort", c.listenPort)
        .put("filter", c.filter)
        .put("igateName", c.igateName)
        .put("source", c.source.name)
        .put("radioToClients", c.radioToClients)
        .put("radioToInternet", c.radioToInternet)
        .put("internetToRadio", c.internetToRadio)
        .put("clientsToRadio", c.clientsToRadio)
        .put("clientsToInternet", c.clientsToInternet)
        .put("internetToClients", c.internetToClients)
        .put("radioToClientsCache", c.radioToClientsCache)
        .put("internetToClientsCache", c.internetToClientsCache)
        .put("applyQar", c.applyQar)
        .put("pingSeconds", c.pingSeconds)
        .put("cacheSeconds", c.cacheSeconds)
        .put("idleTimeoutSeconds", c.idleTimeoutSeconds)
        .put("aisEnabled", c.aisEnabled)
        .put("aisPort", c.aisPort)
        .put("radioToAis", c.radioToAis)
        .put("internetToAis", c.internetToAis)
        .put("radioToAisCache", c.radioToAisCache)
        .put("internetToAisCache", c.internetToAisCache)

    private fun proxyFromJson(j: org.json.JSONObject, d: ProxyConfig): ProxyConfig = ProxyConfig(
        enabled = j.optBoolean("enabled", d.enabled),
        callsign = j.optString("callsign", d.callsign),
        passcode = j.optInt("passcode", d.passcode),
        server = j.optString("server", d.server),
        serverPort = j.optInt("serverPort", d.serverPort),
        listenPort = j.optInt("listenPort", d.listenPort),
        filter = j.optString("filter", d.filter),
        igateName = j.optString("igateName", d.igateName),
        source = runCatching { ProxySource.valueOf(j.optString("source", d.source.name)) }
            .getOrDefault(d.source),
        radioToClients = j.optBoolean("radioToClients", d.radioToClients),
        radioToInternet = j.optBoolean("radioToInternet", d.radioToInternet),
        internetToRadio = j.optBoolean("internetToRadio", d.internetToRadio),
        clientsToRadio = j.optBoolean("clientsToRadio", d.clientsToRadio),
        clientsToInternet = j.optBoolean("clientsToInternet", d.clientsToInternet),
        internetToClients = j.optBoolean("internetToClients", d.internetToClients),
        radioToClientsCache = j.optBoolean("radioToClientsCache", d.radioToClientsCache),
        internetToClientsCache = j.optBoolean("internetToClientsCache", d.internetToClientsCache),
        applyQar = j.optBoolean("applyQar", d.applyQar),
        pingSeconds = j.optLong("pingSeconds", d.pingSeconds),
        cacheSeconds = j.optLong("cacheSeconds", d.cacheSeconds),
        idleTimeoutSeconds = j.optLong("idleTimeoutSeconds", d.idleTimeoutSeconds),
        aisEnabled = j.optBoolean("aisEnabled", d.aisEnabled),
        aisPort = j.optInt("aisPort", d.aisPort),
        radioToAis = j.optBoolean("radioToAis", d.radioToAis),
        internetToAis = j.optBoolean("internetToAis", d.internetToAis),
        radioToAisCache = j.optBoolean("radioToAisCache", d.radioToAisCache),
        internetToAisCache = j.optBoolean("internetToAisCache", d.internetToAisCache),
    )
}