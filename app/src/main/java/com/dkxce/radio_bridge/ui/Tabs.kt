/*
 * Device tab: scan / list / connect. Server tab: mode+port. Log tab.
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dkxce.radio_bridge.app.BridgeConfig
import com.dkxce.radio_bridge.app.ServerMode
import com.dkxce.radio_bridge.app.UiState

@Composable
fun DeviceTab(
    state: UiState,
    isConnected: Boolean,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onScan, enabled = !state.scanning) {
                if (state.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.BluetoothSearching, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.scanning) "Сканирование..." else "Сканировать")
            }
            Spacer(Modifier.weight(1f))
            if (isConnected) {
                Button(onClick = onDisconnect, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Отключить")
                }
            }
        }

        if (state.connectedName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("Подключено: ${state.connectedName} (${state.connectedAddress})",
                    modifier = Modifier.padding(10.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.devices) { d ->
                Card(onClick = { onConnect(d.address) }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name.ifBlank { "<no name>" }, style = MaterialTheme.typography.titleSmall)
                            Text(d.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (state.serviceSummary.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Найденные сервисы:", style = MaterialTheme.typography.labelMedium)
            state.serviceSummary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun ServerTab(
    state: UiState,
    cfg: BridgeConfig,
    onApply: (BridgeConfig) -> Unit,
) {
    var mode by remember(cfg.serverMode) { mutableStateOf(cfg.serverMode) }
    var port by remember(cfg.port) { mutableStateOf(cfg.port.toString()) }
    var igServer by remember(cfg.igateServer) { mutableStateOf(cfg.igateServer) }
    var igPort by remember(cfg.igatePort) { mutableStateOf(cfg.igatePort.toString()) }
    var igClient by remember(cfg.igateClient) { mutableStateOf(cfg.igateClient) }
    var igName by remember(cfg.igateName) { mutableStateOf(cfg.igateName) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Mode selector
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text("Режим: ${mode.name}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ServerMode.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.name) },
                        onClick = { mode = m; expanded = false })
                }
            }
        }

        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Порт") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))

        Text("APRS-IS IGate (для режима APRS-IS):", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(value = igServer, onValueChange = { igServer = it },
            label = { Text("Сервер") }, singleLine = true)
        OutlinedTextField(value = igPort, onValueChange = { igPort = it },
            label = { Text("Порт") }, singleLine = true)
        OutlinedTextField(value = igClient, onValueChange = { igClient = it },
            label = { Text("APRSIS_CLIENT") }, singleLine = true)
        OutlinedTextField(value = igName, onValueChange = { igName = it },
            label = { Text("IGATE_NAME") }, singleLine = true)

        Button(onClick = {
            onApply(BridgeConfig(
                serverMode = mode,
                port = port.toIntOrNull() ?: 8023,
                igateServer = igServer,
                igatePort = igPort.toIntOrNull() ?: 14580,
                igateClient = igClient,
                igateName = igName,
            ))
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Settings, null)
            Spacer(Modifier.width(6.dp))
            Text("Применить и запустить сервер (${if (state.serverRunning) "уже работает" else "старт"})")
        }
        if (state.serverRunning) {
            Text("Сервер работает: ${state.serverMode} :${state.port} · клиентов: ${state.clients}",
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun LogTab(state: UiState, onClearLog: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RX/TX Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClearLog) { Text("Очистить") }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
        ) {
            items(state.log.reversed()) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}