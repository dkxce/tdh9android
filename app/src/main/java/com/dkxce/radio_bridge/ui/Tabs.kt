/*
 ==============================================================================
  Compose tabs: device / server / log / about
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  DeviceTab (scan/connect/history), ServerTab (mode/port/start-stop,
            client list, local addresses), LogTab and AboutTab.
  Russian:  Вкладки интерфейса: Рация / Сервер / Лог / About.
 ==============================================================================
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
import com.dkxce.radio_bridge.igate.IGateConfig

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
                Text(if (state.scanning) "Scanning..." else "Scan")
            }
            Spacer(Modifier.weight(1f))
            if (isConnected) {
                Button(onClick = onDisconnect, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Disconnect")
                }
            }
        }

        if (state.connectedName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("Connected: ${state.connectedName} (${state.connectedAddress})",
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
            Text("History (previously connected):", style = MaterialTheme.typography.labelMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.history) { h ->
                    Card(onClick = { onConnect(h.address) }) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.name.ifBlank { "<no name>" }, style = MaterialTheme.typography.bodyMedium)
                                Text(h.address, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("connect", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (state.serviceSummary.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Found services:", style = MaterialTheme.typography.labelMedium)
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
                Text("Mode: ${mode.label}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ServerMode.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.label) },
                        onClick = { mode = m; expanded = false })
                }
            }
        }

        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") },
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
            Text("Start server")
        }

        if (state.serverRunning) {
            Text("Server running: ${state.serverMode.label} :${state.port} · clients: ${state.clients}",
                color = MaterialTheme.colorScheme.primary)
            if (state.clientsList.isNotEmpty()) {
                Text("Connected clients:", style = MaterialTheme.typography.labelMedium)
                state.clientsList.forEach { c ->
                    Text(c, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("No clients yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onStopServer,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(6.dp))
                Text("Stop server")
            }
        } else {
            Text("Server stopped", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }

        // Local addresses for Wi-Fi/LAN clients to connect to.
        Text("Server local addresses:", style = MaterialTheme.typography.labelMedium)
        val ips = com.dkxce.radio_bridge.net.LocalAddresses.ipv4()
        if (ips.isEmpty()) {
            Text("(no active network / no IP)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ips.forEach { ip ->
                Text("$ip:${port.toIntOrNull() ?: state.port}",
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Connect to one of these addresses from another device on the same network.",
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
            TextButton(onClick = onClearLog) { Text("Clear") }
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
    @Composable
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
        Text("Version: v1.1.0", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Author: dkxce", style = MaterialTheme.typography.titleMedium)
        link("https://github.com/dkxce", "GitHub: https://github.com/dkxce")
        Spacer(Modifier.height(4.dp))
        Text("Project (Android):", style = MaterialTheme.typography.bodySmall)
        link("https://github.com/dkxce/tdh9android",
            "https://github.com/dkxce/tdh9android")
        Spacer(Modifier.height(4.dp))
        Text("Desktop version:", style = MaterialTheme.typography.bodySmall)
        link("https://github.com/dkxce/tdh9desktop",
            "https://github.com/dkxce/tdh9desktop")
        Spacer(Modifier.height(4.dp))
        Text("Main repository:", style = MaterialTheme.typography.bodySmall)
        link("https://github.com/dkxce/Tidradio-TD-H9",
            "https://github.com/dkxce/Tidradio-TD-H9")
        Spacer(Modifier.height(8.dp))
        Text("Bridge between the TIDRadio TD-H9 radio (BLE APRS service 0000af00) and external "
            + "APRS applications via local TCP servers.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** IGate tab: settings + Start/Stop (mirrors Python igate.py). / Вкладка IGate. */
@Composable
fun IGateTab(
    state: UiState,
    onStartIGate: () -> Unit,
    onStopIGate: () -> Unit,
    onSaveIGateConfig: (IGateConfig) -> Unit,
) {
    val cfg = state.igateConfig
    var client by remember(cfg.igateClient) { mutableStateOf(cfg.igateClient) }
    var name by remember(cfg.igateName) { mutableStateOf(cfg.igateName) }
    var pass by remember(cfg.passcode) { mutableStateOf(cfg.passcode.toString()) }
    var server by remember(cfg.server) { mutableStateOf(cfg.server) }
    var port by remember(cfg.port) { mutableStateOf(cfg.port.toString()) }
    var filter by remember(cfg.filter) { mutableStateOf(cfg.filter) }
    var checkTcpip by remember(cfg.checkTcpip) { mutableStateOf(cfg.checkTcpip) }
    var checkNoQar by remember(cfg.checkNoQar) { mutableStateOf(cfg.checkNoQar) }
    var checkNoQao by remember(cfg.checkNoQao) { mutableStateOf(cfg.checkNoQao) }

    val collectCfg = {
        IGateConfig(
            igateClient = client.trim(),
            igateName = name.trim(),
            passcode = pass.toIntOrNull() ?: 0,
            server = server.trim(),
            port = port.toIntOrNull() ?: 14580,
            filter = filter.trim(),
            checkTcpip = checkTcpip,
            checkNoQar = checkNoQar,
            checkNoQao = checkNoQao,
            enabled = true,
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("IGate (Radio <-> APRS-IS)", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(value = client, onValueChange = { client = it },
            label = { Text("APRSIS_CLIENT") }, singleLine = true)
        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text("IGATE_NAME") }, singleLine = true)
        OutlinedTextField(value = pass, onValueChange = { pass = it },
            label = { Text("Passcode") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        OutlinedTextField(value = server, onValueChange = { server = it },
            label = { Text("Server") }, singleLine = true)
        OutlinedTextField(value = port, onValueChange = { port = it },
            label = { Text("Port") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        OutlinedTextField(value = filter, onValueChange = { filter = it },
            label = { Text("Filter (default d/<IGATE_NAME>*)") }, singleLine = true)

        Row {
            Checkbox(checked = checkTcpip, onCheckedChange = { checkTcpip = it })
            Text("Require TCPIP", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        }
        Row {
            Checkbox(checked = checkNoQar, onCheckedChange = { checkNoQar = it })
            Text("Reject qAR", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        }
        Row {
            Checkbox(checked = checkNoQao, onCheckedChange = { checkNoQao = it })
            Text("Reject qAO", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        }

        if (state.igateRunning) {
            Button(onClick = onStopIGate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(6.dp))
                Text("Stop IGate")
            }
            Text("IGate running", color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = {
                onSaveIGateConfig(collectCfg())
                onStartIGate()
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Start IGate")
            }
            Text("IGate stopped", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}