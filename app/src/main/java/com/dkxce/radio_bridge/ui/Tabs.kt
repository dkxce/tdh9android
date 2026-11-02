/*
 * Device tab: scan / list / connect. Server tab: mode+port. Log tab.
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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

        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("История (подключённые ранее):", style = MaterialTheme.typography.labelMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.history) { h ->
                    Card(onClick = { onConnect(h.address) }) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.name.ifBlank { "<no name>" }, style = MaterialTheme.typography.bodyMedium)
                                Text(h.address, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("подключить", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
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
    onStartServer: (BridgeConfig) -> Unit,
    onStopServer: () -> Unit,
    onSaveConfig: (BridgeConfig) -> Unit,
) {
    var mode by remember(state.serverMode) { mutableStateOf(state.serverMode) }
    var port by remember(state.port) { mutableStateOf(state.port.toString()) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Mode selector
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text("Режим: ${mode.label}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ServerMode.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.label) },
                        onClick = { mode = m; expanded = false })
                }
            }
        }

        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Порт") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))

        Button(onClick = {
            val cfg = BridgeConfig(
                serverMode = mode,
                port = port.toIntOrNull() ?: 8023,
            )
            onSaveConfig(cfg)
            onStartServer(cfg)
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("Запустить сервер")
        }

        if (state.serverRunning) {
            Text("Сервер работает: ${state.serverMode.label} :${state.port} · клиентов: ${state.clients}",
                color = MaterialTheme.colorScheme.primary)
            if (state.clientsList.isNotEmpty()) {
                Text("Подключённые клиенты:", style = MaterialTheme.typography.labelMedium)
                state.clientsList.forEach { c ->
                    Text(c, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Клиентов пока нет", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onStopServer,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(6.dp))
                Text("Остановить сервер")
            }
        } else {
            Text("Сервер остановлен", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }

        // Local addresses for Wi-Fi/LAN clients to connect to.
        Text("Локальные адреса сервера:", style = MaterialTheme.typography.labelMedium)
        val ips = com.dkxce.radio_bridge.net.LocalAddresses.ipv4()
        if (ips.isEmpty()) {
            Text("(нет активной сети / IP не определён)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ips.forEach { ip ->
                Text("$ip:${port.toIntOrNull() ?: state.port}",
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Подключитесь к одному из адресов с другого устройства в той же сети.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** About tab: program author and info. / О программе. */
@Composable
fun AboutTab() {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    fun link(url: String, label: String) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { uriHandler.openUri(url) },
        )
    }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("TDH9RadioBridge", style = MaterialTheme.typography.headlineMedium)
        Text("BLE KISS TNC bridge: TIDRadio TD-H9 ⇄ TCP/IP (KISS / AGWPE / APRS-IS)",
            style = MaterialTheme.typography.bodyMedium)
        Text("Версия: v1.1.0", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Автор: dkxce", style = MaterialTheme.typography.titleMedium)
        link("https://github.com/dkxce", "GitHub: https://github.com/dkxce")
        Spacer(Modifier.height(4.dp))
        Text("Проект (Android):", style = MaterialTheme.typography.bodySmall)
        link("https://github.com/dkxce/tdh9android",
            "https://github.com/dkxce/tdh9android")
        Spacer(Modifier.height(4.dp))
        Text("Десктоп-версия (Python):", style = MaterialTheme.typography.bodySmall)
        link("https://github.com/dkxce/Tidradio-TD-H9",
            "https://github.com/dkxce/Tidradio-TD-H9")
        Spacer(Modifier.height(8.dp))
        Text("Мост между рацией TIDRadio TD-H9 (BLE-сервис APRS 0000af00) и внешними "
            + "APRS-приложениями по локальным TCP-серверам.",
            style = MaterialTheme.typography.bodySmall)
    }
}