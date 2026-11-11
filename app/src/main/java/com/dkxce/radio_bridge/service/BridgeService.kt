/*
 ==============================================================================
  Foreground service hosting the BLE bridge and the TCP servers
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.4.0

  English:  Runs the whole bridge: keeps the BLE connection and the selected
            TCP server (Binary KISS / AGWPE / APRS-IS) alive, forwards radio
            frames to clients and client frames to the radio. Foreground
            service (connectedDevice) so it survives the screen turning off.
  Russian:  Фоновый сервис, обеспечивающий работу моста BLE <-> TCP.
 ==============================================================================
 */

package com.dkxce.radio_bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.dkxce.radio_bridge.R
import com.dkxce.radio_bridge.app.AppModel
import com.dkxce.radio_bridge.app.ServerMode
import com.dkxce.radio_bridge.app.UiState
import com.dkxce.radio_bridge.ble.BleBridge
import com.dkxce.radio_bridge.ble.BleDeviceInfo
import com.dkxce.radio_bridge.ble.BleEvents
import com.dkxce.radio_bridge.core.Kiss
import com.dkxce.radio_bridge.core.Ax25
import com.dkxce.radio_bridge.net.AgwTcpServer
import com.dkxce.radio_bridge.net.AprsIsProxyClient
import com.dkxce.radio_bridge.net.AprsIsTcpServer
import com.dkxce.radio_bridge.net.GpsGateProxyClient
import com.dkxce.radio_bridge.net.KissTcpServer
import com.dkxce.radio_bridge.igate.IGateClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the whole bridge. On start it creates the BLE bridge and the TCP
 * servers, starts one server according to the config, and forwards radio
 * frames to clients and client frames to the radio.
 */
class BridgeService : Service() {

    private lateinit var model: AppModel
    private var ble: BleBridge? = null
    private var kissServer: KissTcpServer? = null
    private var agwServer: AgwTcpServer? = null
    private var aprsServer: AprsIsTcpServer? = null
    private var igate: IGateClient? = null
    private var proxy: AprsIsProxyClient? = null
    private var gpsGate: GpsGateProxyClient? = null
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        model = AppModel.getInstance(this)
        model.loadHistory()
        createChannel()
        startForegroundNotify()
        running.set(true)
    }

    override fun onDestroy() {
        running.set(false)
        stopServers()
        stopIGate()
        stopProxy()
        stopGpsGate()
        ble?.disconnect()
        ble = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY
        when (action) {
            ACTION_SCAN -> {
                model.clearDevices()
                ensureBle().stopScanIfActive()
                ensureBle().startScan()
                model.setScanning(true)
                // Auto-stop after 12 s so the UI never stays stuck scanning.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (model.ui.value.scanning) {
                        ensureBle().stopScanIfActive()
                        model.setScanning(false)
                    }
                }, 12000L)
            }
            ACTION_STOP_SCAN -> {
                ensureBle().stopScan()
                model.setScanning(false)
            }
            ACTION_CONNECT -> {
                val addr = intent.getStringExtra(EXTRA_ADDR)
                if (addr != null) ensureBle().connect(addr)
            }
            ACTION_DISCONNECT -> ensureBle().disconnect()
            ACTION_START_SERVER -> startServers(
                intent.getStringExtra(EXTRA_MODE)?.let { runCatching { ServerMode.valueOf(it) }.getOrNull() },
                intent.getIntExtra(EXTRA_PORT, -1),
            )
            ACTION_STOP_SERVER -> stopServers()
            ACTION_START_IGATE -> startIGate()
            ACTION_STOP_IGATE -> stopIGate()
            ACTION_START_PROXY -> startProxy()
            ACTION_STOP_PROXY -> stopProxy()
            ACTION_RESET_PROXY_STATS -> { proxy?.resetStats() }
            ACTION_CLEAR_PROXY_LOG -> { proxy?.clearPacketLog() }
            ACTION_CLEAR_CACHE -> { proxy?.clearCaches() }
            ACTION_START_GPSGATE -> startGpsGate()
            ACTION_STOP_GPSGATE -> stopGpsGate()
            ACTION_UPDATE_GPSGATE -> updateGpsGate()
            ACTION_RESET_GPSGATE_STATS -> { gpsGate?.resetStats() }
            ACTION_ACFG_DOWNLOAD -> acfgDownload()
            ACTION_ACFG_UPLOAD -> acfgUpload()
            ACTION_ACFG_FIELD_GET -> {
                val key = intent.getStringExtra(EXTRA_ACFG_KEY)
                if (key != null) acfgFieldGet(key)
            }
            ACTION_ACFG_FIELD_SET -> {
                val key = intent.getStringExtra(EXTRA_ACFG_KEY)
                if (key != null) acfgFieldSet(key)
            }
            ACTION_SEND_RADIO -> {
                val data = intent.getByteArrayExtra(EXTRA_KISS)
                if (data != null) sendToRadio(data)
            }
        }
        return START_STICKY
    }

    private var bleEvents = object : BleEvents {
        override fun onScanResults(devices: List<BleDeviceInfo>) {
            for (d in devices) {
                model.addDevice(
                    com.dkxce.radio_bridge.app.BleDeviceUi(d.address, d.name, d.rssi))
            }
        }
        override fun onConnected(addr: String, name: String) {
            model.setConnected(addr, name)
            model.recordConnectedDevice(
                com.dkxce.radio_bridge.app.BleDeviceUi(addr, name, 0))
            model.appendLog("[BLE] Connected $name ($addr)")
        }
        override fun onDisconnected(addr: String?) {
            model.setConnected(null, "")
            model.appendLog("[BLE] Disconnected")
        }
        override fun onError(msg: String) {
            model.appendLog("[BLE!] $msg")
            // If an error occurs during a scan, make sure the UI does not stay
            // stuck on "Scanning..." forever.
            if (model.ui.value.scanning) {
                model.setScanning(false)
            }
        }
        override fun onStatus(msg: String) {
            model.appendLog("[BLE] $msg")
        }
        override fun onServices(summary: List<String>, writeUuid: String?, notifyUuid: String?) {
            model.setServices(summary)
            model.appendLog("[BLE] services:")
            summary.forEach { model.appendLog("      $it") }
            model.appendLog("[BLE] write=$writeUuid notify=$notifyUuid")
        }
        override fun onKissFrame(port: Int, command: Int, payload: ByteArray) {
            handleRadioRx(payload)
        }
    }

    private fun ensureBle(): BleBridge {
        ble?.let { return it }
        val b = BleBridge(applicationContext, bleEvents)
        ble = b
        return b
    }

    // ------------------------------------------------------------------ servers
    private fun startServers(forcedMode: ServerMode? = null, forcedPort: Int? = null) {
        val cfg0 = model.loadConfig()
        val mode = forcedMode ?: cfg0.serverMode
        val port = forcedPort ?: cfg0.port
        val cfg = cfg0.copy(serverMode = mode, port = port)
        stopServers()

        fun log(m: String) = model.appendLog(m)
        val onClients = { refreshClientInfo() }

        when (cfg.serverMode) {
            ServerMode.KISS -> {
                val s = KissTcpServer(cfg.port, { payload -> model.appendLog("[KISS <-] ${Ax25.toAprsText(payload)}"); sendToRadio(payload) }, onClients, ::log)
                kissServer = s
                s.start()
                log("[Server] KISS binary on :${cfg.port}")
            }
            ServerMode.AGW -> {
                val s = AgwTcpServer(cfg.port, { payload -> model.appendLog("[AGW <-] ${Ax25.toAprsText(payload)}"); sendToRadio(payload) }, onClients, ::log)
                agwServer = s
                s.start()
                log("[Server] AGWPE on :${cfg.port}")
            }
            ServerMode.APRS_IS -> {
                val s = AprsIsTcpServer(cfg.port, { payload -> model.appendLog("[APRS-IS <-] ${Ax25.toAprsText(payload)}"); sendToRadio(payload) }, onClients, ::log)
                aprsServer = s
                s.start()
                log("[Server] APRS-IS on :${cfg.port}")
            }
        }
        model.setServerRunning(true)
    }

    private fun currentClientCount(): Int {
        var n = kissServer?.clientCount ?: 0
        n += agwServer?.clientCount ?: 0
        n += aprsServer?.clientCount ?: 0
        return n
    }

    private fun currentClientList(): List<String> {
        val out = LinkedHashSet<String>()
        kissServer?.clientList()?.let { out.addAll(it) }
        agwServer?.clientList()?.let { out.addAll(it) }
        aprsServer?.clientList()?.let { out.addAll(it) }
        return out.toList()
    }

    /** Push client count + list to the UI model. */
    private fun refreshClientInfo() {
        model.setClients(currentClientCount())
        model.setClientsList(currentClientList())
    }

    private fun stopServers() {
        kissServer?.stop(); kissServer = null
        agwServer?.stop(); agwServer = null
        aprsServer?.stop(); aprsServer = null
        model.setServerRunning(false)
        model.setClients(0)
        model.setClientsList(emptyList())
    }

    // ------------------------------------------------------------------ radio io
    private fun handleRadioRx(raw: ByteArray) {
        val text = Ax25.toAprsText(raw)
        model.appendLog("[RX] $text")
        // This runs on the main thread (BLE notify callback). Sending to TCP
        // sockets (IGate radio->net and the server broadcasts) must happen off
        // the main thread or Android throws NetworkOnMainThreadException,
        // which also corrupted frames on the receiving radios.
        Thread {
            try {
                // Rule (b): send the received radio frame into the IGate
                // (radio -> net) on a background thread, like Python.
                try { igate?.radioRx(text) } catch (e: Exception) { model.appendLog("[IGate!] radioRx: $e") }
                // Proxy: radio -> clients + internet (IGate qAR correction inside).
                try { proxy?.radioRx(text) } catch (e: Exception) { model.appendLog("[Proxy!] radioRx: $e") }
                val n = currentClientCount()
                if (n == 0) {
                    model.appendLog("[RX] no TCP clients connected, skip broadcast")
                    return@Thread
                }
                try { kissServer?.broadcastRadio(raw) } catch (e: Exception) { model.appendLog("[RX!] KISS broadcast: $e") }
                try { agwServer?.broadcastRadio(raw) } catch (e: Exception) { model.appendLog("[RX!] AGW broadcast: $e") }
                try { aprsServer?.broadcastRadio(raw) } catch (e: Exception) { model.appendLog("[RX!] APRS-IS broadcast: $e") }
            } catch (e: Exception) {
                model.appendLog("[RX!] broadcast loop: $e")
            }
        }.start()
    }

    private fun sendToRadio(ax25Payload: ByteArray) {
        val kissFrame = Kiss.frame(ax25Payload)
        ble?.sendKiss(kissFrame)
    }

    // ------------------------------------------------------------------ IGate
    private fun startIGate() {
        val cfg = model.loadIGateConfig().copy(enabled = true)
        model.saveIGateConfig(cfg)
        if (igate == null) {
            igate = IGateClient(
                cfg = cfg,
                onRxToRadio = { text ->
                    // Network -> Radio (rule a). Transmit the gated packet over
                    // the radio. Mirror Python igate.py: encode the APRS text
                    // back into a binary AX.25 UI-frame, then KISS-frame it.
                    // Log handled inside IGate client.
                    try {
                        val ax25 = Ax25.encodeText(text)
                        model.appendLog("[IGate -> radio] $text")
                        sendToRadio(ax25)
                    } catch (e: Exception) {
                        model.appendLog("[IGate!] encode/tx failed: $e")
                    }
                },
                onLog = { msg -> model.appendLog(msg) },
            )
        } else {
            igate?.updateConfig(cfg)
        }
        igate?.start()
        model.setIGateRunning(true)
        model.appendLog("[IGate] started")
    }

    private fun stopIGate() {
        igate?.stop()
        igate = null
        model.setIGateRunning(false)
        model.appendLog("[IGate] stopped")
    }

    // ------------------------------------------------------------------ proxy
    private fun startProxy() {
        val cfg = model.loadProxyConfig().copy(enabled = true)
        model.saveProxyConfig(cfg)
        if (proxy == null) {
            proxy = AprsIsProxyClient(
                cfg = cfg,
                onTxToRadio = { text ->
                    // internet / clients -> radio. Log and transmit as-is.
                    try {
                        val ax25 = com.dkxce.radio_bridge.core.Ax25.encodeText(text)
                        model.appendLog("[Proxy -> radio] $text")
                        sendToRadio(ax25)
                    } catch (e: Exception) {
                        model.appendLog("[Proxy!] tx to radio failed: $e")
                    }
                },
                onLog = { msg -> model.appendLog(msg) },
            ).also { p ->
                // Push live proxy stats to the UI model periodically.
                p.setOnStatsChanged { pushProxyStats() }
            }
        } else {
            proxy?.updateConfig(cfg)
        }
        proxy?.start()
        model.setProxyRunning(true)
        model.appendLog("[Proxy] started")
    }

    private fun stopProxy() {
        proxy?.stop()
        proxy = null
        model.setProxyRunning(false)
        model.setProxyStats(com.dkxce.radio_bridge.net.ProxyStats())
        model.appendLog("[Proxy] stopped")
    }

    private fun pushProxyStats() {
        val p = proxy ?: return
        model.setProxyStats(p.stats())
    }

    // ------------------------------------------------------------------ gpsgate
    private fun startGpsGate() {
        val cfg = model.loadGpsGateConfig().copy(enabled = true)
        model.saveGpsGateConfig(cfg)
        if (gpsGate == null) {
            gpsGate = GpsGateProxyClient(
                cfg = cfg,
                onLog = { msg -> model.appendLog(msg) },
            ).also { g ->
                g.setOnStatsChanged { pushGpsGateStats() }
            }
        } else {
            gpsGate?.updateConfig(cfg)
        }
        gpsGate?.start()
        model.setGpsGateRunning(true)
        model.appendLog("[GPSGate] started")
    }

    private fun stopGpsGate() {
        gpsGate?.stop()
        gpsGate = null
        model.setGpsGateRunning(false)
        model.setGpsGateStats(com.dkxce.radio_bridge.net.GpsGateStats())
        model.appendLog("[GPSGate] stopped")
    }

    /** Re-apply the persisted config to a running proxy (no restart). */
    private fun updateGpsGate() {
        val g = gpsGate ?: return
        val cfg = model.loadGpsGateConfig()
        g.updateConfig(cfg)
        model.appendLog("[GPSGate] config applied (${cfg.servers.size} server(s))")
    }

    private fun pushGpsGateStats() {
        val g = gpsGate ?: return
        model.setGpsGateStats(g.statsSnapshot())
    }

    // ------------------------------------------------------------------ ACFG
    // Radio-config FBXX (GNSS / APRS / Site Type) read & write via the FF00
    // clone service on the already-open connection. The state lives in
    // AppModel; the UI sends intents and reads state via MainViewModel.
    private var acfgCfgIo: com.dkxce.radio_bridge.ble.CfgIo? = null

    private fun acfgIo(): com.dkxce.radio_bridge.ble.CfgIo? {
        val g = ble?.gattInstance() ?: run {
            model.appendLog("[ACFG!] not connected")
            model.setAcfgBusy(false)
            return null
        }
        if (acfgCfgIo == null) acfgCfgIo = com.dkxce.radio_bridge.ble.CfgIo(ble!!)
        return acfgCfgIo
    }

    private fun acfgDownload() {
        val io = acfgIo() ?: return
        model.setAcfgBusy(true)
        model.setAcfgStatus("Downloading...")
        io.downloadSettings(
            onProgress = { done, total ->
                model.setAcfgProgress(if (total > 0) done.toFloat() / total else 0f)
                model.setAcfgStatus("Downloading ${done}/${total}")
            },
            onDone = { image ->
                model.setAcfgProgress(1f)
                model.setAcfgStatus("Downloaded ${image.size} bytes")
                model.setAcfgBusy(false)
                // decode all fields into the UI state
                val values = LinkedHashMap<String, String>()
                for ((_, fields) in com.dkxce.radio_bridge.acfg.ACFG_GROUPS_BUILT) {
                    for (f in fields) {
                        val raw = image.copyOfRange(8 + f.offset, 8 + f.offset + f.length)
                        val v = com.dkxce.radio_bridge.acfg.decodeAcfgField(f, raw)
                        values[f.key] = v
                    }
                }
                model.setAcfgValues(values)
                model.appendLog("[ACFG] settings downloaded (${values.size} fields)")
            },
            onError = { msg ->
                model.setAcfgBusy(false)
                model.setAcfgStatus("Error: $msg")
                model.appendLog("[ACFG!] $msg")
            },
        )
    }

    private fun acfgUpload() {
        val io = acfgIo() ?: return
        // upload the current UI values as blocks. To keep it simple and safe we
        // encode each field and write the touched blocks (like the desktop
        // per-field Set). If the user changed many fields, this writes them
        // one field at a time over the clone session.
        val values = model.ui.value.acfgValues
        if (values.isEmpty()) {
            model.appendLog("[ACFG!] nothing to upload")
            model.setAcfgBusy(false)
            return
        }
        model.setAcfgBusy(true)
        model.setAcfgStatus("Uploading...")
        // collect encoded bytes for every field that has a non-empty value
        val toWrite = mutableListOf<Pair<com.dkxce.radio_bridge.acfg.AcfgField, ByteArray>>()
        var encodeErr: String? = null
        for ((_, fields) in com.dkxce.radio_bridge.acfg.ACFG_GROUPS_BUILT) {
            for (f in fields) {
                val v = values[f.key]?.trim().orEmpty()
                if (v.isEmpty()) continue
                try {
                    val enc = com.dkxce.radio_bridge.acfg.encodeAcfgField(f, v)
                    toWrite.add(f to enc)
                } catch (e: Exception) {
                    encodeErr = "${f.key}: ${e.message}"
                }
            }
        }
        if (encodeErr != null) {
            model.setAcfgBusy(false)
            model.setAcfgStatus("Encode error: $encodeErr")
            model.appendLog("[ACFG!] encode error: $encodeErr")
            return
        }
        if (toWrite.isEmpty()) {
            model.setAcfgBusy(false)
            model.appendLog("[ACFG!] nothing to write")
            return
        }
        uploadFieldsSequential(toWrite, 0)
    }

    private fun uploadFieldsSequential(
        list: List<Pair<com.dkxce.radio_bridge.acfg.AcfgField, ByteArray>>,
        index: Int,
    ) {
        if (index >= list.size) {
            model.setAcfgBusy(false)
            model.setAcfgProgress(1f)
            model.setAcfgStatus("Uploaded ${list.size} field(s)")
            model.appendLog("[ACFG] upload done (${list.size} field(s))")
            return
        }
        val (field, bytes) = list[index]
        val io = acfgIo() ?: return
        model.setAcfgStatus("Writing ${field.key} (${index + 1}/${list.size})")
        io.uploadField(
            fieldOff = field.offset,
            fieldBytes = bytes,
            onDone = {
                model.setAcfgProgress((index + 1).toFloat() / list.size)
                uploadFieldsSequential(list, index + 1)
            },
            onError = { msg ->
                model.setAcfgBusy(false)
                model.setAcfgStatus("Error writing ${field.key}: $msg")
                model.appendLog("[ACFG!] ${field.key}: $msg")
            },
        )
    }

    private fun acfgFieldGet(key: String) {
        val field = com.dkxce.radio_bridge.acfg.ACFG_FIELDS[key] ?: run {
            model.appendLog("[ACFG!] unknown field $key")
            return
        }
        val io = acfgIo() ?: return
        model.setAcfgBusy(true)
        model.setAcfgStatus("Reading $key...")
        io.downloadField(
            fieldOff = field.offset,
            fieldLen = field.length,
            onRead = { raw ->
                val v = com.dkxce.radio_bridge.acfg.decodeAcfgField(field, raw)
                model.setAcfgValue(key, v)
                model.setAcfgBusy(false)
                model.setAcfgStatus("$key = $v")
                model.appendLog("[ACFG] $key = $v")
            },
            onError = { msg ->
                model.setAcfgBusy(false)
                model.setAcfgStatus("Error reading $key: $msg")
                model.appendLog("[ACFG!] $key: $msg")
            },
        )
    }

    private fun acfgFieldSet(key: String) {
        val field = com.dkxce.radio_bridge.acfg.ACFG_FIELDS[key] ?: run {
            model.appendLog("[ACFG!] unknown field $key")
            return
        }
        val v = model.ui.value.acfgValues[key]?.trim().orEmpty()
        if (v.isEmpty()) {
            model.appendLog("[ACFG!] $key is empty")
            model.setAcfgBusy(false)
            return
        }
        val enc = try {
            com.dkxce.radio_bridge.acfg.encodeAcfgField(field, v)
        } catch (e: Exception) {
            model.setAcfgBusy(false)
            model.setAcfgStatus("Encode error $key: ${e.message}")
            model.appendLog("[ACFG!] $key: ${e.message}")
            return
        }
        val io = acfgIo() ?: return
        model.setAcfgBusy(true)
        model.setAcfgStatus("Writing $key...")
        io.uploadField(
            fieldOff = field.offset,
            fieldBytes = enc,
            onDone = {
                model.setAcfgBusy(false)
                model.setAcfgStatus("$key written")
                model.appendLog("[ACFG] $key = $v")
            },
            onError = { msg ->
                model.setAcfgBusy(false)
                model.setAcfgStatus("Error writing $key: $msg")
                model.appendLog("[ACFG!] $key: $msg")
            },
        )
    }

    // ------------------------------------------------------------------ notification
    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW))
    }

    @Suppress("DEPRECATION")
    private fun startForegroundNotify() {
        // tapping the notification opens the main activity
        val openApp = android.app.PendingIntent.getActivity(
            this, 0,
            android.content.Intent(this, com.dkxce.radio_bridge.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            if (Build.VERSION.SDK_INT >= 23)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_SCAN = "com.dkxce.radio_bridge.ACTION_SCAN"
        const val ACTION_STOP_SCAN = "com.dkxce.radio_bridge.ACTION_STOP_SCAN"
        const val ACTION_CONNECT = "com.dkxce.radio_bridge.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.dkxce.radio_bridge.ACTION_DISCONNECT"
        const val ACTION_START_SERVER = "com.dkxce.radio_bridge.ACTION_START_SERVER"
        const val ACTION_STOP_SERVER = "com.dkxce.radio_bridge.ACTION_STOP_SERVER"
        const val ACTION_START_IGATE = "com.dkxce.radio_bridge.ACTION_START_IGATE"
        const val ACTION_STOP_IGATE = "com.dkxce.radio_bridge.ACTION_STOP_IGATE"
        const val ACTION_START_PROXY = "com.dkxce.radio_bridge.ACTION_START_PROXY"
        const val ACTION_STOP_PROXY = "com.dkxce.radio_bridge.ACTION_STOP_PROXY"
        const val ACTION_RESET_PROXY_STATS = "com.dkxce.radio_bridge.ACTION_RESET_PROXY_STATS"
        const val ACTION_CLEAR_PROXY_LOG = "com.dkxce.radio_bridge.ACTION_CLEAR_PROXY_LOG"
        const val ACTION_CLEAR_CACHE = "com.dkxce.radio_bridge.ACTION_CLEAR_CACHE"
        const val ACTION_START_GPSGATE = "com.dkxce.radio_bridge.ACTION_START_GPSGATE"
        const val ACTION_STOP_GPSGATE = "com.dkxce.radio_bridge.ACTION_STOP_GPSGATE"
        const val ACTION_UPDATE_GPSGATE = "com.dkxce.radio_bridge.ACTION_UPDATE_GPSGATE"
        const val ACTION_RESET_GPSGATE_STATS = "com.dkxce.radio_bridge.ACTION_RESET_GPSGATE_STATS"
        const val ACTION_ACFG_DOWNLOAD = "com.dkxce.radio_bridge.ACTION_ACFG_DOWNLOAD"
        const val ACTION_ACFG_UPLOAD = "com.dkxce.radio_bridge.ACTION_ACFG_UPLOAD"
        const val ACTION_ACFG_FIELD_GET = "com.dkxce.radio_bridge.ACTION_ACFG_FIELD_GET"
        const val ACTION_ACFG_FIELD_SET = "com.dkxce.radio_bridge.ACTION_ACFG_FIELD_SET"
        const val ACTION_SEND_RADIO = "com.dkxce.radio_bridge.ACTION_SEND_RADIO"
        const val EXTRA_ADDR = "addr"
        const val EXTRA_KISS = "kiss"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PORT = "port"
        const val EXTRA_ACFG_KEY = "acfg_key"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIF_ID = 1
    }
}