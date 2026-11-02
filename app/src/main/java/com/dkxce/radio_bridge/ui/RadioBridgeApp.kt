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
import com.dkxce.radio_bridge.app.UiState

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
    onClearLog: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val isConnected = state.connectedAddress != null

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("TDH9RadioBridge · TNC over BLE") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Bluetooth, null) }, label = { Text("Radio") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Server") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Description, null) }, label = { Text("Log") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Info, null) }, label = { Text("About") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> DeviceTab(state, isConnected, onScan, onConnect, onDisconnect)
                1 -> ServerTab(state, onStartServer, onStopServer, onSaveConfig)
                2 -> LogTab(state, onClearLog)
                3 -> AboutTab()
            }
        }
    }
}