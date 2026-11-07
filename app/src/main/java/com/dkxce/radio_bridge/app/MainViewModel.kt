/*
 ==============================================================================
  ViewModel bridging the BridgeService AppModel state to the UI
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  Exposes the UiState StateFlow and settings mutations to Compose.
  Russian:  ViewModel, связывающий состояние AppModel с интерфейсом.
 ==============================================================================
 */

package com.dkxce.radio_bridge.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dkxce.radio_bridge.igate.IGateConfig
import com.dkxce.radio_bridge.net.ProxyConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes [UiState] from the app's [AppModel] to Compose. Settings mutations
 * go directly to [AppModel.saveConfig].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val model = AppModel.getInstance(application.applicationContext)

    init {
        // Load the previously-connected devices right away, so the History list
        // is populated as soon as the app opens (no Scan needed).
        model.loadHistory()
        // Load persisted IGate settings into the UI state.
        model.loadIGateConfig()
        // Load persisted Proxy settings into the UI state.
        model.loadProxyConfig()
    }

    val ui: StateFlow<UiState> = model.ui

    fun saveConfig(cfg: BridgeConfig) = model.saveConfig(cfg)
    fun saveIGateConfig(cfg: IGateConfig) = model.saveIGateConfig(cfg)
    fun saveProxyConfig(cfg: ProxyConfig) = model.saveProxyConfig(cfg)
    fun setScanning(v: Boolean) = model.setScanning(v)
    fun appendLog(text: String) = model.appendLog(text)
    fun clearLog() = model.clearLog()
    fun loadConfig() = model.loadConfig()
    fun resetProxyStats() = model.resetProxyStats()
}