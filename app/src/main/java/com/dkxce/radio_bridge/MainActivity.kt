/*
 * Main activity: permissions, ViewModel, BridgeService control, UI.
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.dkxce.radio_bridge.app.MainViewModel
import com.dkxce.radio_bridge.service.BridgeService
import com.dkxce.radio_bridge.ui.RadioBridgeApp

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ignore result; user can retry via button */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent {
            MaterialTheme {
                val state by viewModel.ui.collectAsState()
                RadioBridgeApp(
                    state = state,
                    onScan = {
                        if (hasBlePermissions()) {
                            viewModel.setScanning(true)
                            startService(BridgeService.ACTION_SCAN)
                        } else ensurePermissions()
                    },
                    onConnect = { addr ->
                        startService(BridgeService.ACTION_CONNECT, addr)
                    },
                    onDisconnect = { startService(BridgeService.ACTION_DISCONNECT) },
                    onStartServer = { startService(BridgeService.ACTION_START_SERVER) },
                    onSaveConfig = viewModel::saveConfig,
                    onClearLog = viewModel::clearLog,
                )
            }
        }
    }

    private fun startService(action: String, addr: String? = null) {
        val i = Intent(this, BridgeService::class.java).setAction(action)
        if (addr != null) i.putExtra(BridgeService.EXTRA_ADDR, addr)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun hasBlePermissions(): Boolean {
        val perms = requiredPermissions()
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.FOREGROUND_SERVICE)
        add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
    }.toTypedArray()

    private fun ensurePermissions() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}