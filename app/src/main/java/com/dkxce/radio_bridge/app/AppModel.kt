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
import com.dkxce.radio_bridge.igate.IGateConfig
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
        val next = (cur + line).takeLast(500)
        _ui.value = _ui.value.copy(log = next)
    }

    fun clearLog() { _ui.value = _ui.value.copy(log = emptyList()) }
}