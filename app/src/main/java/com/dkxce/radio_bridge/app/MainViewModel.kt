/*
 * ViewModel bridging the BridgeService AppModel state to the UI.
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes [UiState] from the app's [AppModel] to Compose. Settings mutations
 * go directly to [AppModel.saveConfig].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val model = AppModel(application.applicationContext)

    val ui: StateFlow<UiState> = model.ui.asStateFlow()

    fun saveConfig(cfg: BridgeConfig) = model.saveConfig(cfg)
    fun setScanning(v: Boolean) = model.setScanning(v)
    fun clearLog() = model.clearLog()
    fun loadConfig() = model.loadConfig()
}