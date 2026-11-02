/*
 * BLE bridge to use a TIDRadio TD-H9 as a KISS TNC over BLE GATT.
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
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

    fun startScan() {
        val a = adapter() ?: return
        scanner = a.bluetoothLeScanner
        // Android 12+: use proper scan callback via registerStaticCallback; for
        // API <31 use the deprecated beginScan path.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            androidxStartScan(a)
        } else {
            legacyStartScan(a)
        }
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
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
                main.post { events.onScanResults(listOf(BleDeviceInfo(d.address, name, result.rssi))) }
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
        val ok = if (android.os.Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(ch, kissFrame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt?.writeCharacteristic(ch)
        }
        if (ok != true) events.onError("BLE write failed")
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