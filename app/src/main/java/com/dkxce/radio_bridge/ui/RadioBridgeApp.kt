/*
 ==============================================================================
  Top-level Compose screen: tabs, device scan, server control, log
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.5.0

  English:  Main Scaffold with bottom navigation (Radio / Server / Log /
            About) wiring the Compose tabs to the app callbacks.
  Russian:  Каркас экрана с вкладками (Рация/Сервер/Лог/About).
 ==============================================================================
 */

package com.dkxce.radio_bridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dkxce.radio_bridge.app.BridgeConfig
import com.dkxce.radio_bridge.app.UiState
import com.dkxce.radio_bridge.igate.IGateConfig
import com.dkxce.radio_bridge.net.GpsGateProxyConfig
import com.dkxce.radio_bridge.net.ProxyConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioBridgeApp(
    state: UiState,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartServer: (BridgeConfig) -> Unit,
    onStopServer: () -> Unit,
    onSaveConfig: (BridgeConfig) -> Unit,
    onStartIGate: () -> Unit,
    onStopIGate: () -> Unit,
    onSaveIGateConfig: (IGateConfig) -> Unit,
    onStartProxy: () -> Unit,
    onStopProxy: () -> Unit,
    onSaveProxyConfig: (ProxyConfig) -> Unit,
    onResetProxyStats: () -> Unit,
    onClearProxyLog: () -> Unit,
    onClearCache: () -> Unit,
    onStartGpsGate: () -> Unit,
    onStopGpsGate: () -> Unit,
    onSaveGpsGateConfig: (GpsGateProxyConfig) -> Unit,
    onResetGpsGateStats: () -> Unit,
    onReapplyGpsGateConfig: () -> Unit,
    onAcfgDownload: () -> Unit,
    onAcfgUpload: () -> Unit,
    onAcfgFieldGet: (String) -> Unit,
    onAcfgFieldSet: (String) -> Unit,
    onAcfgValueChange: (String, String) -> Unit,
    onAcfgTabVisibility: (Boolean) -> Unit,
    onTabVisibilityChange: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onClearLog: () -> Unit,
    onExitApp: () -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
) {
    // visible tabs in order; Radio, Log, About are always shown
    val tabs = buildList {
        add(AppTab(0, "Radio", Icons.Filled.Bluetooth))
        if (state.showServerTab) add(AppTab(1, "Server", Icons.Filled.Settings))
        if (state.showIGateTab) add(AppTab(2, "IGate", Icons.Filled.Cloud))
        if (state.showProxyTab) add(AppTab(3, "Proxy", Icons.Filled.Link))
        if (state.showGpsGateTab) add(AppTab(4, "GPSGate", Icons.Filled.LocationOn))
        if (state.showAcfgTab) add(AppTab(5, "ACFG", Icons.Filled.Settings))
        add(AppTab(6, "Log", Icons.Filled.Description))
        add(AppTab(7, "About", Icons.Filled.Info))
    }
    var selected by remember { mutableStateOf(0) }
    // effective selection: fall back to Radio if the selected tab got hidden
    val effectiveSelected = if (tabs.none { it.key == selected }) 0 else selected
    val isConnected = state.connectedAddress != null

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("TDH9RadioBridge · TNC over BLE") })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = effectiveSelected == t.key, onClick = { selected = t.key },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (effectiveSelected) {
                0 -> DeviceTab(state, isConnected, onScan, onConnect, onDisconnect)
                1 -> ServerTab(state, onStartServer, onStopServer, onSaveConfig)
                2 -> IGateTab(state, onStartIGate, onStopIGate, onSaveIGateConfig)
                3 -> ProxyTab(state, onStartProxy, onStopProxy, onSaveProxyConfig, onResetProxyStats, onClearProxyLog, onClearCache)
                4 -> GpsGateTab(state, onStartGpsGate, onStopGpsGate, onSaveGpsGateConfig, onResetGpsGateStats, onReapplyGpsGateConfig)
                5 -> AcfgTab(state, onAcfgDownload, onAcfgUpload, onAcfgFieldGet, onAcfgFieldSet, onAcfgValueChange)
                6 -> LogTab(state, onClearLog)
                7 -> AboutTab(state, onExitApp, onExportSettings, onImportSettings, onTabVisibilityChange, onAcfgTabVisibility)
            }
        }
    }
}

/** One item of the bottom navigation bar: fixed key, label and icon. */
private data class AppTab(
    val key: Int,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)