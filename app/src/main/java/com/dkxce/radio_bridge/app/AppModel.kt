/*
 * App settings + live UI state (SharedPreferences-backed).
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which protocol server to run. */
enum class ServerMode { KISS, AGW, APRS_IS }

/** IGate-like APRS-IS connection settings (kept for the settings UI). */
data class BridgeConfig(
    val serverMode: ServerMode = ServerMode.KISS,
    val port: Int = 8023,
    val igateServer: String = "rotate.aprs2.net",
    val igatePort: Int = 14580,
    val igateClient: String = "NOCALL-10",
    val igateName: String = "NOCALL-1",
    val igatePasscode: Int = 0,
)

/** Immutable UI state surfaced to Composables. */
data class UiState(
    val scanning: Boolean = false,
    val devices: List<BleDeviceUi> = emptyList(),
    val connectedAddress: String? = null,
    val connectedName: String = "",
    val serviceSummary: List<String> = emptyList(),
    val serverRunning: Boolean = false,
    val serverMode: ServerMode = ServerMode.KISS,
    val port: Int = 8023,
    val clients: Int = 0,
    val log: List<String> = emptyList(),
)

data class BleDeviceUi(val address: String, val name: String = "", val rssi: Int = 0)

/**
 * Single source of truth for settings and UI state. Holds SharedPreferences
 * wiring and exposes StateFlows that the Compose UI observes.
 */
class AppModel(context: Context) {
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
            .putString("igate_server", cfg.igateServer)
            .putInt("igate_port", cfg.igatePort)
            .putString("igate_client", cfg.igateClient)
            .putString("igate_name", cfg.igateName)
            .putInt("igate_passcode", cfg.igatePasscode)
            .apply()
        _ui.value = _ui.value.copy(
            serverMode = cfg.serverMode, port = cfg.port,
        )
    }

    fun loadConfig(): BridgeConfig = BridgeConfig(
        serverMode = ServerMode.valueOf(prefs.getString("server_mode", ServerMode.KISS.name)!!),
        port = prefs.getInt("port", 8023),
        igateServer = prefs.getString("igate_server", "rotate.aprs2.net")!!,
        igatePort = prefs.getInt("igate_port", 14580),
        igateClient = prefs.getString("igate_client", "NOCALL-10")!!,
        igateName = prefs.getString("igate_name", "NOCALL-1")!!,
        igatePasscode = prefs.getInt("igate_passcode", 0),
    )

    // --- UI state mutators (called from ViewModel/service) ---

    fun setScanning(v: Boolean) { _ui.value = _ui.value.copy(scanning = v) }
    fun addDevice(d: BleDeviceUi) {
        if (_ui.value.devices.any { it.address == d.address }) return
        _ui.value = _ui.value.copy(devices = _ui.value.devices + d)
    }
    fun clearDevices() { _ui.value = _ui.value.copy(devices = emptyList()) }
    fun setConnected(addr: String?, name: String) {
        _ui.value = _ui.value.copy(connectedAddress = addr, connectedName = name)
    }
    fun setServices(summary: List<String>) { _ui.value = _ui.value.copy(serviceSummary = summary) }
    fun setServerRunning(running: Boolean) { _ui.value = _ui.value.copy(serverRunning = running) }
    fun setClients(n: Int) { _ui.value = _ui.value.copy(clients = n) }

    fun appendLog(text: String) {
        val cur = _ui.value.log
        val next = (cur + text).takeLast(500)
        _ui.value = _ui.value.copy(log = next)
    }

    fun clearLog() { _ui.value = _ui.value.copy(log = emptyList()) }
}