/*
 ==============================================================================
  BLE bridge: use a TIDRadio TD-H9 as a KISS TNC over BLE GATT
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  Wraps the Android BluetoothGatt API. Scans, connects, discovers
            services, resolves write/notify characteristics (preferring Odmaster
            af01/af02, falling back to ff02/ffe2 and ff01/ffe1) and pipes
            incoming bytes into a KissParser. Mirrors the Python ble_radio.
  Russian:  BLE-мост: рация как KISS TNC (сервис APRS 0000af00, write af01,
            notify af02).
 ==============================================================================
 */

package com.dkxce.radio_bridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dkxce.radio_bridge.core.Kiss
import com.dkxce.radio_bridge.core.KissFrameCallback
import com.dkxce.radio_bridge.core.KissParser
import java.util.UUID

/** Callbacks delivered on the main thread. */
interface BleEvents {
    fun onScanResults(devices: List<BleDeviceInfo>)
    fun onConnected(addr: String, name: String)
    fun onDisconnected(addr: String?)
    fun onError(msg: String)
    /** Informational status line (Bluetooth state, scan progress, ...). */
    fun onStatus(msg: String)
    fun onServices(summary: List<String>, writeUuid: String?, notifyUuid: String?)
    fun onKissFrame(port: Int, command: Int, payload: ByteArray)
}

data class BleDeviceInfo(val address: String, val name: String = "", val rssi: Int = 0) {
    val label: String get() = (if (name.isBlank()) "<no name>" else name) + " ($address)"
}

/**
 * Wraps the Android BluetoothGatt API. Serially connects, discovers services,
 * resolves write/notify characteristics (preferring the Odmaster APRS UUIDs,
 * falling back to first write/notify found), subscribes to notify and pipes
 * incoming bytes into a KissParser.
 */
@SuppressLint("MissingPermission")
class BleBridge(
    private val context: Context,
    private val events: BleEvents,
) {
    // Odmaster APRS service UUIDs.
    companion object {
        val SERVICE_APRS = UUID.fromString("0000af00-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE = UUID.fromString("0000af01-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY = UUID.fromString("0000af02-0000-1000-8000-00805f9b34fb")
        val FALLBACK_WRITE = listOf(
            CHAR_WRITE,
            UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb"),
        )
        val FALLBACK_NOTIFY = listOf(
            CHAR_NOTIFY,
            UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val kissParser = KissParser(KissFrameCallback { p, c, d ->
        events.onKissFrame(p, c, d)
    })
    private var scanner: BluetoothLeScanner? = null
    private var connectedAddr: String? = null

    val isConnected: Boolean get() = gatt?.services?.isNotEmpty() == true

    private fun adapter(): BluetoothAdapter? {
        // Prefer the static default adapter (works without extra permissions),
        // fall back to the manager-based one if needed.
        @Suppress("DEPRECATION")
        var a: BluetoothAdapter? = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: Exception) {
            events.onStatus("BT adapter error: $e")
            null
        }
        if (a == null) {
            try {
                val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                a = mgr?.adapter
            } catch (e: Exception) {
                events.onStatus("BT manager error: $e")
            }
        }
        return a
    }

    private fun locationEnabled(): Boolean {
        try {
            val locMgr = context.getSystemService(Context.LOCATION_SERVICE)
                    as? android.location.LocationManager
            return locMgr?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) {
            events.onStatus("GPS: check error: $e")
            return false
        }
    }

    private fun btStateText(a: BluetoothAdapter): String {
        @Suppress("DEPRECATION")
        return when (a.state) {
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN(${a.state})"
        }
    }

    fun startScan() {
        val a = adapter()
        if (a == null) {
            events.onStatus("BT: no adapter (Bluetooth not supported)")
            events.onError("Bluetooth not supported on this device")
            return
        }
        // The location service (GPS / device location) must be ON for BLE scanning
        // to return devices on most Android versions (Bluetooth scan is treated
        // as a location request unless the app opts out). Log a clear hint if off.
        val loc = locationEnabled()
        events.onStatus("GPS: location service ${if (loc) "ON" else "OFF"}")
        if (!loc) {
            events.onStatus("Hint: enable Location (GPS) so BLE scan can find devices")
        }
        @Suppress("DEPRECATION")
        val on = try {
            a.isEnabled
        } catch (e: Exception) {
            events.onStatus("BT: isEnabled check error: $e")
            false
        }
        events.onStatus("BT: state=${btStateText(a)} enabled=$on")
        if (!on) {
            events.onStatus("BT: requesting enable ...")
            // Try to ask the user to turn Bluetooth on.
            try {
                @Suppress("DEPRECATION")
                a.enable()
            } catch (e: Exception) {
                events.onStatus("BT: cannot auto-enable ($e), ask user")
            }
            val recheck = try { a.isEnabled } catch (e: Exception) { false }
            if (!recheck) {
                events.onError("Bluetooth is OFF. Enable it to scan.")
                return
            }
            events.onStatus("BT: now enabled")
        }
        val scanner = try {
            a.bluetoothLeScanner
        } catch (e: Exception) {
            events.onStatus("BT: scanner unavailable ($e)")
            events.onError("BLE scanner unavailable: ${e.message}")
            return
        }
        this.scanner = scanner
        events.onStatus("BT: starting scan ...")
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            androidxStartScan(a)
        } else {
            legacyStartScan(a)
        }
    }

    @SuppressLint("MissingPermission")
    private fun legacyStartScan(a: BluetoothAdapter) {
        val cb = object : BluetoothAdapter.LeScanCallback {
            override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
                if (device == null) return
                main.post {
                    events.onScanResults(listOf(BleDeviceInfo(device.address, device.name ?: "", rssi)))
                }
            }
        }
        @Suppress("DEPRECATION")
        a.startLeScan(cb)
        scanWorker = cb
    }

    private var scanWorker: Any? = null
    private var scanCallback: Any? = null

    @SuppressLint("MissingPermission")
    private fun androidxStartScan(a: BluetoothAdapter) {
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result == null) return
                val d = result.device
                val name = d.name ?: ""
                events.onStatus("BT: found ${d.address}")
                main.post { events.onScanResults(listOf(BleDeviceInfo(d.address, name, result.rssi))) }
            }
            override fun onScanFailed(errorCode: Int) {
                events.onStatus("BT: scan failed code=$errorCode")
            }
        }
        scanCallback = callback
        @Suppress("DEPRECATION")
        val filters = listOf<ScanFilter>()
        a.bluetoothLeScanner.startScan(filters, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    }

    fun stopScan() {
        val a = adapter() ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                (scanCallback as? ScanCallback)?.let { a.bluetoothLeScanner.stopScan(it) }
            } else {
                @Suppress("DEPRECATION")
                a.stopLeScan(scanWorker as BluetoothAdapter.LeScanCallback?)
            }
        } catch (_: Exception) { }
        scanCallback = null; scanWorker = null
    }

    /** Stop an ongoing scan if one is active, without erroring. */
    fun stopScanIfActive() {
        if (scanCallback != null || scanWorker != null) stopScan()
    }

    fun connect(address: String) {
        val device = adapter()?.getRemoteDevice(address) ?: run {
            events.onError("Unknown device $address"); return
        }
        connectedAddr = address
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Exception) { }
    }

    fun close() {
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
    }

    /** Write a ready-made KISS frame to the write characteristic. */
    fun sendKiss(kissFrame: ByteArray) {
        val ch = writeChar ?: return
        val g = gatt
        if (g == null) {
            events.onError("BLE write failed: not connected")
            return
        }
        // write-without-response: the synchronous boolean only tells whether the
        // request was queued to the stack, NOT whether it was transmitted. Real
        // outcome is reported later in onCharacteristicWrite. So we do not treat
        // "ok != true" as an error here to avoid false "write failed" spam.
        val queued = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, kissFrame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            events.onError("BLE write exception: $e")
            null
        }
        if (queued == false) {
            events.onError("BLE write rejected by stack")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    main.post { events.onDisconnected(connectedAddr) }
                    connectedAddr = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                events.onError("Service discovery failed (status $status)")
                return
            }
            resolveCharacters(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            main.post { kissParser.feed(characteristic.value) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) events.onError("Write status $status")
        }
    }

    /** Pick write/notify characteristics; prefer Odmaster af01/af02, else first
     * write/notify found anywhere. Then enable notify and emit service summary. */
    @SuppressLint("MissingPermission")
    private fun resolveCharacters(g: BluetoothGatt) {
        var chosenW: BluetoothGattCharacteristic? = null
        var chosenN: BluetoothGattCharacteristic? = null
        var otherW: BluetoothGattCharacteristic? = null
        var otherN: BluetoothGattCharacteristic? = null
        val summary = ArrayList<String>()

        for (svc in g.services) {
            val isAprs = svc.uuid == SERVICE_APRS
            for (ch in svc.characteristics) {
                val props = ch.properties
                val hasW = (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                val hasN = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                summary.add("${svc.uuid}  ${ch.uuid}  w=$hasW n=$hasN")

                if (hasW) {
                    if (FALLBACK_WRITE.contains(ch.uuid) || ch.uuid == CHAR_WRITE) {
                        if (isAprs && chosenW == null) chosenW = ch
                        else if (otherW == null) otherW = ch
                    } else if (otherW == null) otherW = ch
                }
                if (hasN) {
                    if (FALLBACK_NOTIFY.contains(ch.uuid) || ch.uuid == CHAR_NOTIFY) {
                        if (isAprs && chosenN == null) chosenN = ch
                        else if (otherN == null) otherN = ch
                    } else if (otherN == null) otherN = ch
                }
            }
        }
        writeChar = chosenW ?: otherW
        notifyChar = chosenN ?: otherN

        // enable notifications
        notifyChar?.let { n ->
            g.setCharacteristicNotification(n, true)
            val descriptor = n.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }

        val addr = connectedAddr
        if (addr != null) {
            val name = try { adapter()?.getRemoteDevice(addr)?.name } catch (_: Exception) { null } ?: addr
            main.post {
                events.onServices(summary, writeChar?.uuid?.toString(), notifyChar?.uuid?.toString())
                events.onConnected(addr, name)
            }
        }
    }
}