/*
 ==============================================================================
  Main activity: permissions, ViewModel, BridgeService control, UI
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.4.0

  English:  App entry point. Requests Bluetooth/location/notification
            permissions, starts the foreground BridgeService and hosts the
            Compose UI.
  Russian:  Главная активность: разрешения, запуск сервиса, интерфейс.
 ==============================================================================
 */

package com.dkxce.radio_bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ignore result; user can retry via button */ }

    private enum class SettingsAction { EXPORT, IMPORT }
    private var pendingSettingsAction: SettingsAction? = null

    private val settingsFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val action = pendingSettingsAction ?: return@registerForActivityResult
        pendingSettingsAction = null
        if (uri != null) {
            // keep read/write access for future exports into the same folder
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            when (action) {
                SettingsAction.EXPORT -> {
                    runCatching {
                        viewModel.exportSettingsToFolder(uri.toString())
                        viewModel.appendLog("[Settings] exported: $uri/TDH9RadioBridgeBackup.json")
                    }.onFailure { viewModel.appendLog("[Settings!] export failed: $it") }
                }
                SettingsAction.IMPORT -> {
                    runCatching {
                        viewModel.importSettingsFromFolder(uri.toString())
                        viewModel.appendLog("[Settings] imported: $uri/TDH9RadioBridgeBackup.json")
                    }.onFailure { viewModel.appendLog("[Settings!] import failed: $it") }
                }
            }
        } else {
            viewModel.appendLog("[Settings] folder selection cancelled")
        }
    }

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
                        } else {
                            viewModel.clearLog()
                            viewModel.appendLog("[BLE] Need Bluetooth/location permissions to scan")
                            ensurePermissions()
                        }
                    },
                    onConnect = { addr ->
                        startService(BridgeService.ACTION_CONNECT, addr)
                    },
                    onDisconnect = { startService(BridgeService.ACTION_DISCONNECT) },
                    onStartServer = { cfg ->
                        startService(
                            BridgeService.ACTION_START_SERVER,
                            cfg.serverMode.name,
                            cfg.port,
                        )
                    },
                    onStopServer = {
                        startService(BridgeService.ACTION_STOP_SERVER)
                    },
                    onStartIGate = {
                        startService(BridgeService.ACTION_START_IGATE)
                    },
                    onStopIGate = {
                        startService(BridgeService.ACTION_STOP_IGATE)
                    },
                    onSaveIGateConfig = viewModel::saveIGateConfig,
                    onStartProxy = {
                        startService(BridgeService.ACTION_START_PROXY)
                    },
                    onStopProxy = {
                        startService(BridgeService.ACTION_STOP_PROXY)
                    },
                    onSaveProxyConfig = viewModel::saveProxyConfig,
                    onResetProxyStats = {
                        startService(BridgeService.ACTION_RESET_PROXY_STATS)
                    },
                    onClearProxyLog = {
                        startService(BridgeService.ACTION_CLEAR_PROXY_LOG)
                    },
                    onClearCache = {
                        startService(BridgeService.ACTION_CLEAR_CACHE)
                    },
                    onStartGpsGate = {
                        startService(BridgeService.ACTION_START_GPSGATE)
                    },
                    onStopGpsGate = {
                        startService(BridgeService.ACTION_STOP_GPSGATE)
                    },
                    onSaveGpsGateConfig = viewModel::saveGpsGateConfig,
                    onResetGpsGateStats = {
                        startService(BridgeService.ACTION_RESET_GPSGATE_STATS)
                    },
                    onReapplyGpsGateConfig = {
                        startService(BridgeService.ACTION_UPDATE_GPSGATE)
                    },
                    onAcfgDownload = {
                        startService(BridgeService.ACTION_ACFG_DOWNLOAD)
                    },
                    onAcfgUpload = {
                        startService(BridgeService.ACTION_ACFG_UPLOAD)
                    },
                    onAcfgFieldGet = { key ->
                        startService(BridgeService.ACTION_ACFG_FIELD_GET, key)
                    },
                    onAcfgFieldSet = { key ->
                        startService(BridgeService.ACTION_ACFG_FIELD_SET, key)
                    },
                    onAcfgValueChange = { key, value ->
                        viewModel.setAcfgValue(key, value)
                    },
                    onAcfgTabVisibility = { show ->
                        viewModel.saveAcfgTabVisibility(show)
                    },
                    onTabVisibilityChange = viewModel::saveTabVisibility,
                    onSaveConfig = viewModel::saveConfig,
                    onClearLog = viewModel::clearLog,
                    onExitApp = { finishAffinity(); exitProcess(0) },
                    onExportSettings = {
                        pendingSettingsAction = SettingsAction.EXPORT
                        settingsFolderLauncher.launch(null)
                    },
                    onImportSettings = {
                        pendingSettingsAction = SettingsAction.IMPORT
                        settingsFolderLauncher.launch(null)
                    },
                )
            }
        }
    }

    private fun startService(action: String, addr: String? = null) {
        val i = Intent(this, BridgeService::class.java).setAction(action)
        if (addr != null) i.putExtra(BridgeService.EXTRA_ADDR, addr)
        launch(i)
    }

    private fun startService(action: String, key: String) {
        val i = Intent(this, BridgeService::class.java).setAction(action)
        i.putExtra(BridgeService.EXTRA_ACFG_KEY, key)
        launch(i)
    }

    private fun startService(action: String, mode: String?, port: Int?) {
        val i = Intent(this, BridgeService::class.java).setAction(action)
        if (mode != null) i.putExtra(BridgeService.EXTRA_MODE, mode)
        if (port != null) i.putExtra(BridgeService.EXTRA_PORT, port)
        launch(i)
    }

    private fun launch(i: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun hasBlePermissions(): Boolean {
        val perms = runtimePermissions()
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun runtimePermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // BLE scanning counts as a location use unless the app opts out
        // (BLUETOOTH_SCAN without android:usesPermissionFlags="neverForLocation").
        // So request location on all versions, including Android 12+.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun ensurePermissions() {
        val needed = runtimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}