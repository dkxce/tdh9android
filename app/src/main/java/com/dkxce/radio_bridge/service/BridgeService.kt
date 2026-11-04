/*
 ==============================================================================
  Foreground service hosting the BLE bridge and the TCP servers
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

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
import com.dkxce.radio_bridge.net.AprsIsTcpServer
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

    // ------------------------------------------------------------------ notification
    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW))
    }

    @Suppress("DEPRECATION")
    private fun startForegroundNotify() {
        val n: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
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
        const val ACTION_SEND_RADIO = "com.dkxce.radio_bridge.ACTION_SEND_RADIO"
        const val EXTRA_ADDR = "addr"
        const val EXTRA_KISS = "kiss"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIF_ID = 1
    }
}