/*
 * Top-level Compose screen: tabs, device scan, server control, log.
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
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
import com.dkxce.radio_bridge.app.ServerMode
import com.dkxce.radio_bridge.app.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioBridgeApp(
    state: UiState,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartServer: () -> Unit,
    onSaveConfig: (BridgeConfig) -> Unit,
    onClearLog: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var cfg by remember { mutableStateOf(BridgeConfig()) }
    val isConnected = state.connectedAddress != null

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("RadioBridge · TNC over BLE") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Bluetooth, null) }, label = { Text("Рация") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Сервер") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Description, null) }, label = { Text("Лог") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> DeviceTab(state, isConnected, onScan, onConnect, onDisconnect)
                1 -> ServerTab(state, cfg) { c -> cfg = c; onSaveConfig(c); onStartServer() }
                2 -> LogTab(state, onClearLog)
            }
        }
    }
}