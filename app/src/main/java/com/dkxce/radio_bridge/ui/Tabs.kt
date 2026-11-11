/*
 ==============================================================================
  Compose tabs: device / server / log / about
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.4.0

  English:  DeviceTab (scan/connect/history), ServerTab (mode/port/start-stop,
            client list, local addresses), LogTab and AboutTab.
  Russian:  Вкладки интерфейса: Рация / Сервер / Лог / About.
 ==============================================================================
 */

package com.dkxce.radio_bridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Image
import com.dkxce.radio_bridge.app.BridgeConfig
import com.dkxce.radio_bridge.app.ServerMode
import com.dkxce.radio_bridge.app.UiState
import com.dkxce.radio_bridge.igate.IGateConfig
import com.dkxce.radio_bridge.net.GpsGateImeiSource
import com.dkxce.radio_bridge.net.GpsGateProto
import com.dkxce.radio_bridge.net.GpsGateProxyConfig
import com.dkxce.radio_bridge.net.GpsGateServer
import com.dkxce.radio_bridge.net.GpsGateTimestampMode
import com.dkxce.radio_bridge.net.ProxyConfig
import com.dkxce.radio_bridge.net.ProxySource
import androidx.compose.runtime.toMutableStateList

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

    // Auto-save server settings on every field change
    LaunchedEffect(mode, port) {
        onSaveConfig(BridgeConfig(serverMode = mode, port = port.toIntOrNull() ?: 8023))
    }

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
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column {
                        state.clientsList.forEach { c ->
                            Text(c, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
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
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column {
                    ips.forEach { ip ->
                        Text("$ip:${port.toIntOrNull() ?: state.port}",
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
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
        // selectable text: long-press / drag to select, then copy
        androidx.compose.foundation.text.selection.SelectionContainer(
            modifier = Modifier.fillMaxSize(),
        ) {
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
}

/** About tab: program author and info. / О программе. */
@Composable
fun AboutTab(
    state: UiState,
    onExitApp: () -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    onTabVisibilityChange: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onAcfgTabVisibility: (Boolean) -> Unit,
) {
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("TDH9RadioBridge", style = MaterialTheme.typography.headlineMedium)
        Text("BLE KISS TNC bridge: TIDRadio TD-H9 ⇄ TCP/IP (KISS / AGWPE / APRS-IS / AIS)",
            style = MaterialTheme.typography.bodyMedium)
        Text("Version: v1.4.0 extended", style = MaterialTheme.typography.bodySmall)
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
        Spacer(Modifier.height(16.dp))
        // ---- tab visibility toggles (Enable ... Mode) ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Tab visibility", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.showServerTab,
                        onCheckedChange = { onTabVisibilityChange(it, state.showIGateTab, state.showProxyTab, state.showGpsGateTab) })
                    Text("Enable Server Mode", modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.showIGateTab,
                        onCheckedChange = { onTabVisibilityChange(state.showServerTab, it, state.showProxyTab, state.showGpsGateTab) })
                    Text("Enable IGate Mode", modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.showProxyTab,
                        onCheckedChange = { onTabVisibilityChange(state.showServerTab, state.showIGateTab, it, state.showGpsGateTab) })
                    Text("Enable Proxy Mode", modifier = Modifier.weight(1f))
                }
                // GPSGateProxy checkbox visibility: decided on first launch by system
                // language, then persisted; import can override it (see AppModel)
                if (state.showGpsGateCheckbox) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.showGpsGateTab,
                            onCheckedChange = { onTabVisibilityChange(state.showServerTab, state.showIGateTab, state.showProxyTab, it) })
                        Text("Enable GPSGateProxy Mode", modifier = Modifier.weight(1f))
                    }
                }
                // ACFG tab: radio GNSS/APRS config read/write (off by default)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.showAcfgTab,
                        onCheckedChange = { onAcfgTabVisibility(it) })
                    Text("Show ACFG tab", modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onExportSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Save, null); Spacer(Modifier.width(6.dp)); Text("Export settings (JSON)")
        }
        Button(
            onClick = onImportSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.SettingsBackupRestore, null); Spacer(Modifier.width(6.dp)); Text("Import settings (JSON)")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onExitApp,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.ExitToApp, null); Spacer(Modifier.width(6.dp)); Text("Exit application")
        }
    }
}

/** Proxy tab: APRS-IS proxy settings, stats, clients and packet log. */
@Composable
fun ProxyTab(
    state: UiState,
    onStartProxy: () -> Unit,
    onStopProxy: () -> Unit,
    onSaveProxyConfig: (ProxyConfig) -> Unit,
    onResetProxyStats: () -> Unit,
    onClearProxyLog: () -> Unit,
    onClearCache: () -> Unit,
) {
    val pcfg = state.proxyConfig
    var callsign by remember(pcfg.callsign) { mutableStateOf(pcfg.callsign) }
    var pass by remember(pcfg.passcode) { mutableStateOf(pcfg.passcode.toString()) }
    var server by remember(pcfg.server) { mutableStateOf(pcfg.server) }
    var serverPort by remember(pcfg.serverPort) { mutableStateOf(pcfg.serverPort.toString()) }
    var listenPort by remember(pcfg.listenPort) { mutableStateOf(pcfg.listenPort.toString()) }
    var filter by remember(pcfg.filter) { mutableStateOf(pcfg.filter) }
    var igateName by remember(pcfg.igateName) { mutableStateOf(pcfg.igateName) }
    var source by remember(pcfg.source) { mutableStateOf(pcfg.source) }
    var radioToClients by remember(pcfg.radioToClients) { mutableStateOf(pcfg.radioToClients) }
    var radioToInternet by remember(pcfg.radioToInternet) { mutableStateOf(pcfg.radioToInternet) }
    var internetToRadio by remember(pcfg.internetToRadio) { mutableStateOf(pcfg.internetToRadio) }
    var clientsToRadio by remember(pcfg.clientsToRadio) { mutableStateOf(pcfg.clientsToRadio) }
    var clientsToInternet by remember(pcfg.clientsToInternet) { mutableStateOf(pcfg.clientsToInternet) }
    var internetToClients by remember(pcfg.internetToClients) { mutableStateOf(pcfg.internetToClients) }
    var radioToClientsCache by remember(pcfg.radioToClientsCache) { mutableStateOf(pcfg.radioToClientsCache) }
    var internetToClientsCache by remember(pcfg.internetToClientsCache) { mutableStateOf(pcfg.internetToClientsCache) }
    var applyQar by remember(pcfg.applyQar) { mutableStateOf(pcfg.applyQar) }
    var pingSeconds by remember(pcfg.pingSeconds) { mutableStateOf(pcfg.pingSeconds.toString()) }
    var cacheSeconds by remember(pcfg.cacheSeconds) { mutableStateOf(pcfg.cacheSeconds.toString()) }
    var idleTimeoutSeconds by remember(pcfg.idleTimeoutSeconds) { mutableStateOf(pcfg.idleTimeoutSeconds.toString()) }
    var aisEnabled by remember(pcfg.aisEnabled) { mutableStateOf(pcfg.aisEnabled) }
    var aisPort by remember(pcfg.aisPort) { mutableStateOf(pcfg.aisPort.toString()) }
    var radioToAis by remember(pcfg.radioToAis) { mutableStateOf(pcfg.radioToAis) }
    var internetToAis by remember(pcfg.internetToAis) { mutableStateOf(pcfg.internetToAis) }
    var radioToAisCache by remember(pcfg.radioToAisCache) { mutableStateOf(pcfg.radioToAisCache) }
    var internetToAisCache by remember(pcfg.internetToAisCache) { mutableStateOf(pcfg.internetToAisCache) }

    val collectCfg = {
        ProxyConfig(
            callsign = callsign.trim(),
            passcode = pass.toIntOrNull() ?: 0,
            server = server.trim(),
            serverPort = serverPort.toIntOrNull() ?: 14580,
            listenPort = listenPort.toIntOrNull() ?: 14580,
            filter = filter.trim(),
            igateName = igateName.trim(),
            source = source,
            radioToClients = radioToClients,
            radioToInternet = radioToInternet,
            internetToRadio = internetToRadio,
            clientsToRadio = clientsToRadio,
            clientsToInternet = clientsToInternet,
            internetToClients = internetToClients,
            radioToClientsCache = radioToClientsCache,
            internetToClientsCache = internetToClientsCache,
            applyQar = applyQar,
            pingSeconds = pingSeconds.toLongOrNull() ?: 90,
            cacheSeconds = cacheSeconds.toLongOrNull() ?: 90,
            idleTimeoutSeconds = idleTimeoutSeconds.toLongOrNull() ?: 300,
            aisEnabled = aisEnabled,
            aisPort = aisPort.toIntOrNull() ?: 10110,
            radioToAis = radioToAis,
            internetToAis = internetToAis,
            radioToAisCache = radioToAisCache,
            internetToAisCache = internetToAisCache,
            enabled = true,
        )
    }

    // Auto-save settings on every field change (Russian: автоматическое
    // сохранение настроек при любом изменении поля).
    LaunchedEffect(
        callsign, pass, server, serverPort, listenPort, filter, igateName, source,
        radioToClients, radioToInternet, internetToRadio, clientsToRadio, applyQar,
        clientsToInternet, internetToClients, radioToClientsCache, internetToClientsCache,
        pingSeconds, cacheSeconds, idleTimeoutSeconds, aisEnabled, aisPort,
        radioToAis, internetToAis, radioToAisCache, internetToAisCache,
    ) {
        onSaveProxyConfig(collectCfg())
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("APRS-IS Proxy (Bridge)", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(value = callsign, onValueChange = { callsign = it },
            label = { Text("Callsign (upstream)") }, singleLine = true)
        OutlinedTextField(value = pass, onValueChange = { pass = it },
            label = { Text("Passcode (0 = auto)") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        OutlinedTextField(value = server, onValueChange = { server = it },
            label = { Text("Server") }, singleLine = true)
        OutlinedTextField(value = serverPort, onValueChange = { serverPort = it },
            label = { Text("Server port (upstream APRS-IS)") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        OutlinedTextField(value = listenPort, onValueChange = { listenPort = it },
            label = { Text("Client port (local listener)") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        OutlinedTextField(value = filter, onValueChange = { filter = it },
            label = { Text("Filter (default d/<IGATE_NAME>*)") }, singleLine = true)
        OutlinedTextField(value = igateName, onValueChange = { igateName = it },
            label = { Text("IGATE_NAME") }, singleLine = true)

        // upstream credentials source
        var srcExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { srcExpanded = true }) {
                Text("Credentials: ${source.name}")
            }
            DropdownMenu(expanded = srcExpanded, onDismissRequest = { srcExpanded = false }) {
                ProxySource.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(s.name) },
                        onClick = { source = s; srcExpanded = false })
                }
            }
        }

        // forwarding switches
        Text("Forwarding:", style = MaterialTheme.typography.labelLarge)
        // radio out
        Row { Checkbox(checked = radioToInternet, onCheckedChange = { radioToInternet = it })
            Text("radio -> internet", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = radioToClients, onCheckedChange = { radioToClients = it })
            Text("radio -> clients", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = radioToClientsCache, onCheckedChange = { radioToClientsCache = it })
            Text("radio -> clients cache", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = radioToAis, onCheckedChange = { radioToAis = it })
            Text("radio -> ais", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = radioToAisCache, onCheckedChange = { radioToAisCache = it })
            Text("radio -> ais cache", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Spacer(Modifier.height(4.dp))
        // internet out
        Row { Checkbox(checked = internetToRadio, onCheckedChange = { internetToRadio = it })
            Text("internet -> radio", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = internetToClients, onCheckedChange = { internetToClients = it })
            Text("internet -> clients", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = internetToClientsCache, onCheckedChange = { internetToClientsCache = it })
            Text("internet -> clients cache", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = internetToAis, onCheckedChange = { internetToAis = it })
            Text("internet -> ais", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = internetToAisCache, onCheckedChange = { internetToAisCache = it })
            Text("internet -> ais cache", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Spacer(Modifier.height(4.dp))
        // clients out
        Row { Checkbox(checked = clientsToRadio, onCheckedChange = { clientsToRadio = it })
            Text("clients -> radio", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = clientsToInternet, onCheckedChange = { clientsToInternet = it })
            Text("clients -> internet", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        Row { Checkbox(checked = applyQar, onCheckedChange = { applyQar = it })
            Text("apply ,qAR,<IGATE_NAME> on radio out", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }

        // timing
        Text("Timing:", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(value = pingSeconds, onValueChange = { pingSeconds = it },
            label = { Text("Ping (s)") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        OutlinedTextField(value = cacheSeconds, onValueChange = { cacheSeconds = it },
            label = { Text("Cache (s)") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        OutlinedTextField(value = idleTimeoutSeconds, onValueChange = { idleTimeoutSeconds = it },
            label = { Text("Idle timeout (s)") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))

        // AIS feed (read-only NMEA to AIS clients)
        Text("AIS feed:", style = MaterialTheme.typography.labelLarge)
        Row { Checkbox(checked = aisEnabled, onCheckedChange = { aisEnabled = it })
            Text("Enable AIS", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
        OutlinedTextField(value = aisPort, onValueChange = { aisPort = it },
            label = { Text("AIS port") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        // (radio/internet -> AIS forwarding switches live in the Forwarding group above)

        if (state.proxyRunning) {
            Button(onClick = onStopProxy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop Proxy")
            }
            Text("Proxy running${if (state.proxyStats.upstreamConnected) " · upstream connected" else ""}" +
                (if (state.proxyStats.effectiveCallsign.isNotEmpty()) " · ${state.proxyStats.effectiveCallsign}" else ""),
                color = MaterialTheme.colorScheme.primary)

            // counters
            Text("Packets:", style = MaterialTheme.typography.labelLarge)
            val s = state.proxyStats
            Text("RX radio: ${s.rxRadio}   TX radio: ${s.txRadio}")
            Text("RX internet: ${s.rxInternet}   TX internet: ${s.txInternet}")
            Text("RX clients: ${s.rxClients}   TX clients: ${s.txClients}")
            Text("TX AIS: ${s.txAis}")
            TextButton(onClick = onResetProxyStats) { Text("Reset stats") }
            TextButton(onClick = onClearCache) { Text("Clear cache") }

            // active clients
            if (s.clientList.isNotEmpty()) {
                Text("Active clients:", style = MaterialTheme.typography.labelLarge)
                s.clientList.forEach { c ->
                    Text(c, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("No clients", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // packet log (last 20)
            if (s.packetsLog.isNotEmpty()) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Last packets:", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearProxyLog) { Text("Clear") }
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column {
                        s.packetsLog.take(20).forEach { p ->
                            Text(p, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                }
            }
        } else {
            Button(onClick = {
                onSaveProxyConfig(collectCfg())
                onStartProxy()
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start Proxy")
            }
            Text("Proxy stopped", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
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

    // Auto-save settings on every field change
    LaunchedEffect(client, name, pass, server, port, filter, checkTcpip, checkNoQar, checkNoQao) {
        onSaveIGateConfig(collectCfg())
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
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

/** GPSGate proxy tab: local Franson GPSGate HTTP server + remote forwarders. */
@Composable
fun GpsGateTab(
    state: UiState,
    onStartGpsGate: () -> Unit,
    onStopGpsGate: () -> Unit,
    onSaveGpsGateConfig: (GpsGateProxyConfig) -> Unit,
    onResetGpsGateStats: () -> Unit,
    onReapplyConfig: () -> Unit,
) {
    val gcfg = state.gpsGateConfig
    var listenPort by remember(gcfg.listenPort) { mutableStateOf(gcfg.listenPort.toString()) }
    var timestampMode by remember(gcfg.timestampMode) { mutableStateOf(gcfg.timestampMode) }
    val servers = remember(gcfg.servers) { gcfg.servers.toMutableStateList() }
    // presets: loaded from the network (falls back to built-in list)
    val presets = rememberGpsGatePresets()

    fun collectCfg(): GpsGateProxyConfig {
        return GpsGateProxyConfig(
            enabled = true,
            listenPort = listenPort.toIntOrNull() ?: 12014,
            timestampMode = timestampMode,
            servers = servers.toList(),
        )
    }

    // Auto-save on field change
    LaunchedEffect(listenPort, timestampMode, servers.toList()) {
        onSaveGpsGateConfig(collectCfg())
        onReapplyConfig()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("GPSGate Proxy (Franson GPSGate HTTP)", style = MaterialTheme.typography.titleMedium)
        Text("Local HTTP server for GPSGate trackers (GET), MapMyTracks (POST) and OkMap (RAW)",
            style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(value = listenPort, onValueChange = { listenPort = it },
            label = { Text("Local HTTP port") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))

        // timezone preset: how the $FRCMD timestamp is re-interpreted
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Timezone: ", style = MaterialTheme.typography.bodyMedium)
            Box {
                var tzExpanded by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { tzExpanded = true }) { Text(timestampMode.display) }
                DropdownMenu(expanded = tzExpanded, onDismissRequest = { tzExpanded = false }) {
                    GpsGateTimestampMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(m.display) },
                            onClick = { timestampMode = m; tzExpanded = false })
                    }
                }
            }
        }

        // servers
        Text("Target servers:", style = MaterialTheme.typography.labelLarge)
        servers.forEachIndexed { idx, s ->
            key(idx) {
                GpsGateServerCard(
                    server = s,
                    presets = presets,
                    onChange = { servers[idx] = it },
                    onRemove = { servers.removeAt(idx) },
                )
            }
        }
        Button(onClick = {
            if (servers.size < 8) {
                servers.add(GpsGateServer(name = "Server ${servers.size + 1}", enabled = true,
                    host = "vega.livegpstracks.ru", port = 3333,
                    proto = GpsGateProto.RTT006, imeiSource = GpsGateImeiSource.CRC_FRCMD))
            }
        }, enabled = servers.size < 8) { Text("Add server") }
        if (servers.size >= 8) {
            Text("Maximum 8 servers", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // control + stats
        if (state.gpsGateRunning) {
            Button(onClick = onStopGpsGate,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop GPSGate Proxy")
            }
            val s = state.gpsGateStats
            Text("Running on :${gcfg.listenPort}", color = MaterialTheme.colorScheme.primary)
            Text("RX: ${s.rxPackets}   TX: ${s.txPackets}   Failed: ${s.failedPackets}")
            TextButton(onClick = onResetGpsGateStats) { Text("Reset stats") }
            if (s.packetsLog.isNotEmpty()) {
                Text("Last packets:", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column {
                        s.packetsLog.take(20).forEach { p ->
                            Text(p, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            Button(onClick = {
                onSaveGpsGateConfig(collectCfg())
                onStartGpsGate()
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start GPSGate Proxy")
            }
            Text("GPSGate proxy stopped", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Default preset GPSGate servers (used when the remote list is unavailable). */
private val GPSGATE_PRESETS_DEFAULT = listOf(
    "vega.livegpstracks.ru:3333",
    "leo.livegpstracks.ru:3333",
    "orion.livegpstracks.com:3333",
)

/** Remote source of the GPSGate forwarding-server presets. */
private const val GPSGATE_PRESETS_URL =
    "https://raw.githubusercontent.com/dkxce/tdh9android/refs/heads/main/Resources/gpsgatecfg.json"

/**
 * Load the preset server list from the network (asynchronously).
 * Falls back to the built-in list on any failure (offline / bad JSON).
 */
@Composable
private fun rememberGpsGatePresets(): List<String> {
    var presets by remember { mutableStateOf(GPSGATE_PRESETS_DEFAULT) }
    LaunchedEffect(Unit) {
        presets = try {
            val conn = java.net.URL(GPSGATE_PRESETS_URL).openConnection()
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val text = conn.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONObject(text).optJSONArray("forwarding_services")
            val list = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotBlank() }?.let { list.add(it) }
                }
            }
            if (list.isEmpty()) GPSGATE_PRESETS_DEFAULT else list
        } catch (_: Exception) {
            GPSGATE_PRESETS_DEFAULT
        }
    }
    return presets
}

/**
 * One outbound server card: enable checkbox, host:port field (with a preset
 * picker and a "custom" free-text option), protocol and IMEI rule editor.
 */
@Composable
private fun GpsGateServerCard(
    server: GpsGateServer,
    presets: List<String>,
    onChange: (GpsGateServer) -> Unit,
    onRemove: () -> Unit,
) {
    // host:port kept as a single editable text; value initialised from server
    val hpState = remember { mutableStateOf("${server.host}:${server.port}") }
    // preset picker state: null = custom (free text)
    var preset by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = server.enabled,
                    onCheckedChange = { onChange(server.copy(enabled = it)) })
                OutlinedTextField(
                    value = server.name,
                    onValueChange = { onChange(server.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = onRemove) { Text("Remove") }
            }

            // host:port + preset picker
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hpState.value,
                    onValueChange = { v ->
                        hpState.value = v
                        val h = v.substringBefore(':')
                        val ps = v.substringAfter(':', "")
                        if (!v.contains(':')) {
                            onChange(server.copy(host = v, port = server.port))
                        } else if (ps.isNotEmpty() && ps.all { it.isDigit() }) {
                            onChange(server.copy(host = h, port = ps.toInt()))
                        }
                    },
                    label = { Text("host:port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Box {
                    var open by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { open = true }) { Text("Preset") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        presets.forEach { p ->
                            DropdownMenuItem(text = { Text(p) },
                                onClick = {
                                    preset = p
                                    hpState.value = p
                                    val h = p.substringBefore(':')
                                    val pr = p.substringAfter(':', "").toIntOrNull() ?: 0
                                    onChange(server.copy(host = h, port = pr))
                                    open = false
                                })
                        }
                        DropdownMenuItem(text = { Text("custom") },
                            onClick = { preset = null; open = false })
                    }
                }
            }

            // protocol
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Protocol: ", style = MaterialTheme.typography.bodyMedium)
                var protoExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { protoExpanded = true }) { Text(server.proto.display) }
                    DropdownMenu(expanded = protoExpanded, onDismissRequest = { protoExpanded = false }) {
                        GpsGateProto.entries.forEach { p ->
                            DropdownMenuItem(text = { Text(p.display) },
                                onClick = { onChange(server.copy(proto = p)); protoExpanded = false })
                        }
                    }
                }
            }

            // imei source
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("IMEI: ", style = MaterialTheme.typography.bodyMedium)
                var srcExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { srcExpanded = true }) { Text(server.imeiSource.name) }
                    DropdownMenu(expanded = srcExpanded, onDismissRequest = { srcExpanded = false }) {
                        GpsGateImeiSource.entries.forEach { src ->
                            DropdownMenuItem(text = { Text(src.name) },
                                onClick = { onChange(server.copy(imeiSource = src)); srcExpanded = false })
                        }
                    }
                }
            }
            when (server.imeiSource) {
                GpsGateImeiSource.EXPLICIT -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = server.imeiExplicit,
                            onValueChange = { v ->
                                val lower = v.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
                                onChange(server.copy(imeiExplicit = lower.take(16)))
                            },
                            label = { Text("IMEI (16 hex)") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = {
                            val chars = "0123456789abcdef"
                            val rnd = (1..16).map { chars.random() }.joinToString("")
                            onChange(server.copy(imeiExplicit = rnd))
                        }) { Text("RANDOM") }
                    }
                }
                GpsGateImeiSource.CRC_TEXT -> {
                    val computed = if (server.imeiText.isNotEmpty())
                        com.dkxce.radio_bridge.net.GpsGateProtocol.crc64Hex(server.imeiText) else ""
                    OutlinedTextField(value = server.imeiText,
                        onValueChange = { onChange(server.copy(imeiText = it)) },
                        label = { Text("IMEI text (CRC64 -> IMEI)") }, singleLine = true)
                    // selectable, so the computed CRC64 can be copied
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text("IMEI: $computed", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                GpsGateImeiSource.CRC_FRCMD ->
                    Text("IMEI = CRC64(sender id)\n(\$FRCMD sender or OkMap uuid)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** ACFG tab: radio GNSS / APRS / Site Type settings read + write (FF00 clone). */
@Composable
fun AcfgTab(
    state: UiState,
    onAcfgDownload: () -> Unit,
    onAcfgUpload: () -> Unit,
    onAcfgFieldGet: (String) -> Unit,
    onAcfgFieldSet: (String) -> Unit,
    onAcfgValueChange: (String, String) -> Unit = { _, _ -> },
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ACFG — Radio Configuration (GNSS / APRS / Site Type)",
            style = MaterialTheme.typography.titleMedium)

        // status + progress
        if (state.acfgBusy || state.acfgProgress > 0f) {
            LinearProgressIndicator(
                progress = { state.acfgProgress },
                modifier = Modifier.fillMaxWidth())
        }
        if (state.acfgStatus.isNotEmpty()) {
            Text(state.acfgStatus, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }

        // top actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAcfgDownload, enabled = !state.acfgBusy,
                modifier = Modifier.weight(1f)) {
                Icon(androidx.compose.material.icons.Icons.Filled.Save, null)
                Spacer(Modifier.width(6.dp)); Text("Download all")
            }
            Button(onClick = onAcfgUpload, enabled = !state.acfgBusy,
                modifier = Modifier.weight(1f)) {
                Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(6.dp)); Text("Upload all")
            }
        }

        // field groups
        for ((group, fields) in com.dkxce.radio_bridge.acfg.ACFG_GROUPS_BUILT) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group, style = MaterialTheme.typography.labelLarge)
                    fields.forEach { field ->
                        AcfgFieldRow(
                            field = field,
                            value = state.acfgValues[field.key] ?: "",
                            tableValue = state.acfgValues["icon_table"] ?: "Primary /",
                            busy = state.acfgBusy,
                            onValueChange = { onAcfgValueChange(field.key, it) },
                            onTableChange = { tableLabel -> onAcfgValueChange("icon_table", tableLabel) },
                            onGet = { onAcfgFieldGet(field.key) },
                            onSet = { onAcfgFieldSet(field.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AcfgFieldRow(
    field: com.dkxce.radio_bridge.acfg.AcfgField,
    value: String,
    tableValue: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onTableChange: (String) -> Unit = {},
    onGet: () -> Unit,
    onSet: () -> Unit,
) {
    var editing by remember { mutableStateOf(value) }
    // keep the local editor in sync when the state value changes (loaded from radio)
    LaunchedEffect(field.key, value) { editing = value }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // label + current value summary
        Column(Modifier.weight(1f)) {
            Text(field.label, style = MaterialTheme.typography.bodySmall)
            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // edit control
        when {
            field.key == "icon_symbol" -> {
                AppIconPickerButton(
                    tableValue = tableValue,
                    busy = busy,
                    onPicked = { label, table ->
                        // choosing an icon also updates the symbol table
                        onValueChange(label)
                        onTableChange(if (table == "secondary") "Alternate \\" else "Primary /")
                    },
                )
            }
            field.isChoice && field.choices.isNotEmpty() -> {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
                        Text(value.ifBlank { field.choices.firstOrNull() ?: "" })
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        field.choices.forEach { c ->
                            DropdownMenuItem(text = { Text(c) },
                                onClick = { onValueChange(c); expanded = false })
                        }
                    }
                }
            }
            else -> {
                OutlinedTextField(
                    value = editing,
                    onValueChange = { editing = it; onValueChange(it) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.width(160.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onGet, enabled = !busy) { Text("Get", style = MaterialTheme.typography.labelSmall) }
        TextButton(onClick = onSet, enabled = !busy && (editing.isNotEmpty() || value.isNotEmpty())) { Text("Set", style = MaterialTheme.typography.labelSmall) }
    }
}

/**
 * Button that opens the APRS icon picker dialog; onPicked(label) is called with
 * the "N: /sym - name" label of the chosen icon.
 */
@Composable
private fun AppIconPickerButton(
    tableValue: String,
    busy: Boolean,
    onPicked: (String, String) -> Unit,
) {
    val isAlternate = tableValue.startsWith("Alternate")
    val icons = if (isAlternate)
        com.dkxce.radio_bridge.acfg.AcfgIcons.SECONDARY
    else com.dkxce.radio_bridge.acfg.AcfgIcons.PRIMARY
    val tableName = if (isAlternate) "secondary" else "primary"
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { showDialog = true }, enabled = !busy) { Text("Pick…") }

    if (showDialog) {
        val dialog = Dialog(onDismissRequest = { showDialog = false }) {
            Card(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("APRS icon — ${if (isAlternate) "Secondary \\" else "Primary /"}",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    // grid of icons
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(icons) { ic ->
                            val bmp = remember(ic.b64) {
                                try {
                                    val bytes = android.util.Base64.decode(ic.b64, android.util.Base64.DEFAULT)
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (e: Exception) { null }
                            }
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        onPicked(com.dkxce.radio_bridge.acfg.iconLabel(ic.n, tableName), tableName)
                                        showDialog = false
                                    }
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = ic.name,
                                        modifier = Modifier.size(28.dp))
                                } else {
                                    Spacer(Modifier.size(28.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(ic.sym, style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.width(48.dp))
                                Text(ic.name.ifBlank { ic.sym }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}