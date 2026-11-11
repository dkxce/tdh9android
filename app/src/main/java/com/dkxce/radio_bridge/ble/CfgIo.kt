/*
 ==============================================================================
  CfgIo: TD-H9 settings image read/write over the FF00 clone service
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.5.0

  English:  Runs FF00 clone-mode transactions on the ALREADY-OPEN GATT
            connection (the same one used for KISS). Mirrors the Python
            ble_radio.py: enter clone mode with the MAGIC handshake, read
            each 32-byte block with 0x52, write with 0x57 + checksum + ACK
            0x06, exit with 0x45.

            IMPORTANT: all BluetoothGatt calls (setCharacteristicNotification,
            writeDescriptor, writeCharacteristic) MUST run on the main thread,
            and notifications must be active BEFORE the handshake starts —
            otherwise the first responses of the radio are never delivered and
            every read times out. This class posts every GATT operation to the
            main thread and blocks the worker until it completes.
  Russian:  Чтение/запись образа настроек TD-H9 по сервису FF00 на уже
            открытом GATT-соединении (порт логики ble_radio.py). Все
            GATT-вызовы выполняются в главном потоке и синхронизируются
            с рабочим потоком.
 ==============================================================================
 */

package com.dkxce.radio_bridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * FF00 clone-mode I/O over an already-open [BluetoothGatt].
 *
 * The GATT object is the shared connection used by [BleBridge] for KISS; we do
 * not disconnect or reconnect. The class subscribes to the ff01 notify
 * characteristic, exchanges data, then restores normal operation.
 */
class CfgIo(
    private val bridge: BleBridge,
) {
    private val gatt: BluetoothGatt get() = bridge.gattInstance()
            ?: throw RuntimeException("not connected")
    companion object {
        val SERVICE_FF00 = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val CHAR_FF_WRITE = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CHAR_FF_NOTIFY = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

        const val MEM_SIZE = 0x3140
        const val IMAGE_SIZE = 0x3148   // 8-byte identity + MEM_SIZE
        const val BLOCK = 0x20
        val MAGIC = byteArrayOf(0x50, 0x56.toByte(), 0x4F, 0x4A, 0x48, 0x5C.toByte(), 0x14)
    }

    private val main = Handler(Looper.getMainLooper())

    private var incoming = ArrayList<Byte>()
    private val incomingLock = Any()
    private val wakeRef = AtomicReference(CountDownLatch(1))

    // results
    class Result {
        var image: ByteArray? = null          // full image for download
        var fieldRaw: ByteArray? = null       // raw field bytes for downloadField
        var error: String? = null
        var progressDone = 0
        var progressTotal = 0
    }

    private var notifyChar: BluetoothGattCharacteristic? = null

    /** Locate ff01 notify + ff02 write on the FF00 service. */
    @SuppressLint("MissingPermission")
    private fun resolve(): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val svc = gatt.getService(SERVICE_FF00) ?: return null
        var write: BluetoothGattCharacteristic? = null
        var notify: BluetoothGattCharacteristic? = null
        for (ch in svc.characteristics) {
            if (ch.uuid == CHAR_FF_WRITE) write = ch
            else if (ch.uuid == CHAR_FF_NOTIFY) notify = ch
        }
        if (write == null || notify == null) return null
        return write to notify
    }

    // ------------------------------------------------------------------ sync primitives
    // Run a GATT operation on the main thread and wait for it to finish, so the
    // worker (background) thread sees a deterministic order. Android's BLE stack
    // serialises operations anyway, but the calls themselves must come from the
    // thread that owns the looper where the GATT callbacks are delivered.

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        var t: Throwable? = null
        main.post {
            try { block() } catch (e: Throwable) { t = e } finally { done.countDown() }
        }
        try {
            if (!done.await(10, TimeUnit.SECONDS)) throw RuntimeException("main-thread op timeout")
        } catch (e: InterruptedException) {
            throw RuntimeException("interrupted: $e")
        }
        t?.let { throw RuntimeException("GATT op failed: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun setNotifyEnabled(n: BluetoothGattCharacteristic, enable: Boolean) {
        runOnMain {
            try {
                gatt.setCharacteristicNotification(n, enable)
            } catch (e: Exception) {
                throw RuntimeException("setCharacteristicNotification: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNotifyDescriptor(n: BluetoothGattCharacteristic, enable: Boolean) {
        val desc = n.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: throw RuntimeException("no CCCD descriptor on ff01")
        // Some firmware answers the clone protocol with INDICATIONS (CCCD=0x0002)
        // rather than NOTIFICATIONS (0x0001). bleak auto-selects; we must too,
        // otherwise onCharacteristicChanged never fires and every read times out.
        val value = if (enable) {
            val isIndicate = (n.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            if (isIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        val st = bridge.writeDescriptorAwait(desc, value)
        if (st != BluetoothGatt.GATT_SUCCESS)
            throw RuntimeException("CCCD write status $st")
    }

    /** Enable ff01 notify + CCCD, then let the stack settle a moment so the
     *  descriptor write reaches the peripheral before the handshake bytes. */
    private fun installNotify(notify: BluetoothGattCharacteristic) {
        notifyChar = notify
        setNotifyEnabled(notify, true)
        writeNotifyDescriptor(notify, true)
        // BLE stack needs a beat to actually apply the subscription; without it
        // the first radio replies (handshake ACK/identity) are silently dropped.
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        if (CfgIoCompanionFlags.onCloneModeChanged != null) {
            CfgIoCompanionFlags.onCloneModeChanged?.invoke(true)
        }
    }

    private fun uninstallNotify() {
        val n = notifyChar ?: return
        notifyChar = null
        try { writeNotifyDescriptor(n, false) } catch (_: Exception) {}
        try { setNotifyEnabled(n, false) } catch (_: Exception) {}
        if (CfgIoCompanionFlags.onCloneModeChanged != null) {
            CfgIoCompanionFlags.onCloneModeChanged?.invoke(false)
        }
    }

    private fun pushNotify(data: ByteArray) {
        synchronized(incomingLock) {
            for (b in data) incoming.add(b)
        }
        wakeRef.get().countDown()
    }

    /** Read exactly [n] bytes from the accumulated notify buffer. */
    private fun readExact(n: Int, timeoutMs: Long = 2500, step: String = ""): ByteArray {
        val out = ArrayList<Byte>(n)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size < n) {
            synchronized(incomingLock) {
                if (incoming.isNotEmpty()) {
                    val need = n - out.size
                    val take = if (need <= incoming.size) need else incoming.size
                    for (i in 0 until take) out.add(incoming.removeAt(0))
                }
            }
            if (out.size >= n) break
            if (System.currentTimeMillis() > deadline) {
                val buffered = synchronized(incomingLock) { incoming.size }
                throw RuntimeException("read timeout${if (step.isNotEmpty()) " at [$step]" else ""} " +
                        "(have ${out.size}/$n, buffered $buffered)")
            }
            val left = deadline - System.currentTimeMillis()
            val w = wakeRef.get()
            if (w.count > 0) {
                try {
                    w.await(maxOf(1, left), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    throw RuntimeException("interrupted")
                }
            }
            wakeRef.set(CountDownLatch(1))
        }
        return ByteArray(out.size) { out[it] }
    }

    private fun wireEventCallback() {
        CfgIoCompanionFlags.setNotifySink { data -> pushNotify(data) }
    }

    private fun unwireEventCallback() {
        CfgIoCompanionFlags.clearNotifySink()
    }

    // ------------------------------------------------------------------ public API
    /**
     * Download the full settings image (0x3148 bytes).
     * Callbacks: onProgress(done, total), onDone(image), onError(msg) — all on
     * the main thread.
     */
    fun downloadSettings(
        onProgress: ((Int, Int) -> Unit)? = null,
        onDone: ((ByteArray) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        Thread({
            val res = Result()
            try {
                val pair = resolve() ?: throw RuntimeException("FF00 clone service not found")
                val write = pair.first; val notify = pair.second
                installNotify(notify)
                wireEventCallback()
                enterCloneMode(write)
                val image = ByteArray(IMAGE_SIZE)
                for (address in 0 until MEM_SIZE step BLOCK) {
                    val cmd = byteArrayOf(
                        0x52, ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), BLOCK.toByte())
                    writeGatt(write, cmd)
                    val block = readBlock(address)
                    System.arraycopy(block, 0, image, 8 + address, BLOCK)
                    res.progressDone = address + BLOCK
                    res.progressTotal = MEM_SIZE
                    main.post { onProgress?.invoke(res.progressDone, res.progressTotal) }
                }
                exitCloneMode(write)
                uninstallNotify()
                unwireEventCallback()
                res.image = image
                main.post { onDone?.invoke(image) }
            } catch (e: Exception) {
                res.error = "Download failed: ${e.message}"
                runCatching { uninstallNotify() }
                runCatching { unwireEventCallback() }
                main.post { onError?.invoke(res.error!!) }
            }
        }, "cfg-download").start()
    }

    private fun readBlock(address: Int): ByteArray {
        val expected = byteArrayOf(
            0x57, ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), BLOCK.toByte())
        var header = readExact(4, step = "hdr@0x%04X".format(address))
        if (!header.contentEquals(expected)) {
            val nxt = readExact(1, step = "hdr-shift@0x%04X".format(address))
            val shifted = byteArrayOf(header[1], header[2], header[3], nxt[0])
            if (shifted.contentEquals(expected)) {
                header = shifted
            }
        }
        if (!header.contentEquals(expected))
            throw RuntimeException("Invalid read header at 0x%04X: %s".format(address, hex(header)))
        val data = readExact(BLOCK, step = "data@0x%04X".format(address))
        try { readExact(1, 100, step = "chk@0x%04X".format(address)) } catch (_: Exception) {}
        return data
    }

    private fun enterCloneMode(write: BluetoothGattCharacteristic) {
        command(write, MAGIC, step = "magic-ack")
        writeGatt(write, byteArrayOf(0x02))
        readExact(8, step = "identity")          // 8-byte identity
        command(write, byteArrayOf(0x06), step = "pmode-ack")  // ACK the identity -> ACK 0x06
    }

    private fun command(write: BluetoothGattCharacteristic, data: ByteArray, step: String = "") {
        writeGatt(write, data)
        val ack = readExact(1, step = step)
        if (ack[0] != 0x06.toByte()) throw RuntimeException("expected ACK, got ${hex(ack)}")
    }

    @SuppressLint("MissingPermission")
    private fun writeGatt(write: BluetoothGattCharacteristic, data: ByteArray) {
        val st = bridge.writeAwait(write, data)
        if (st != BluetoothGatt.GATT_SUCCESS)
            throw RuntimeException("write status $st")
    }

    private fun exitCloneMode(write: BluetoothGattCharacteristic) {
        try { writeGatt(write, byteArrayOf(0x45)) } catch (_: Exception) {}
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    // ------------------------------------------------------------------ full write
    /**
     * Write the full settings image (0x3148 bytes) over FF00; each block is
     * 0x57 addr-hi addr-lo len payload chk, ACKed with 0x06.
     */
    fun uploadSettings(
        image: ByteArray,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        Thread({
            try {
                if (image.size != IMAGE_SIZE)
                    throw RuntimeException("Bad image length: ${image.size}")
                val pair = resolve() ?: throw RuntimeException("FF00 clone service not found")
                val write = pair.first; val notify = pair.second
                installNotify(notify)
                wireEventCallback()
                enterCloneMode(write)
                val res = Result()
                for (address in 0 until MEM_SIZE step BLOCK) {
                    val payload = image.copyOfRange(8 + address, 8 + address + BLOCK)
                    val chk = payload.fold(0) { acc, b -> (acc + (b.toInt() and 0xFF)) and 0xFF }
                    val frame = byteArrayOf(
                        0x57, ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(),
                        BLOCK.toByte()) + payload + byteArrayOf(chk.toByte())
                    writeGatt(write, frame)
                    val ack = readExact(1, 5000)
                    if (ack[0] != 0x06.toByte())
                        throw RuntimeException("write ack expected at 0x%04X".format(address))
                    // verify (best-effort, like Python)
                    try {
                        writeGatt(write, byteArrayOf(0x52,
                            ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), BLOCK.toByte()))
                        val hd = readExact(4, 2000)
                        if (hd.contentEquals(byteArrayOf(0x57,
                                ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), BLOCK.toByte()))) {
                            val data = readExact(BLOCK, 2000)
                            try { readExact(1, 100) } catch (_: Exception) {}
                            if (!data.contentEquals(payload))
                                throw RuntimeException("verify mismatch at 0x%04X".format(address))
                        }
                    } catch (_: Exception) {}
                    res.progressDone = address + BLOCK
                    res.progressTotal = MEM_SIZE
                    main.post { onProgress?.invoke(res.progressDone, res.progressTotal) }
                }
                exitCloneMode(write)
                uninstallNotify()
                unwireEventCallback()
                main.post { onDone?.invoke() }
            } catch (e: Exception) {
                runCatching { uninstallNotify() }
                runCatching { unwireEventCallback() }
                main.post { onError?.invoke("Upload failed: ${e.message}") }
            }
        }, "cfg-upload").start()
    }

    // ------------------------------------------------------------------ single field
    /** 32-byte block addresses covering the field at [off]..[off]+[len]. */
    private fun fieldBlocks(off: Int, len: Int): List<Int> {
        val first = (off / BLOCK) * BLOCK
        val last = ((off + len - 1) / BLOCK) * BLOCK
        return (first..last step BLOCK).toList()
    }

    /**
     * Read only the blocks that cover one field; invoke onRead(fieldRawBytes).
     */
    fun downloadField(
        fieldOff: Int,
        fieldLen: Int,
        onRead: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread({
            try {
                val pair = resolve() ?: throw RuntimeException("FF00 clone service not found")
                val write = pair.first; val notify = pair.second
                installNotify(notify)
                wireEventCallback()
                enterCloneMode(write)
                val blocks = LinkedHashMap<Int, ByteArray>()
                for (addr in fieldBlocks(fieldOff, fieldLen)) {
                    writeGatt(write, byteArrayOf(0x52,
                        ((addr ushr 8) and 0xFF).toByte(), (addr and 0xFF).toByte(), BLOCK.toByte()))
                    blocks[addr] = readBlock(addr)
                }
                exitCloneMode(write)
                uninstallNotify()
                unwireEventCallback()
                // splice the field bytes out of the raw blocks (mirror of Python download_field)
                val raw = ByteArray(fieldLen)
                var rawPos = 0
                for (addr in fieldBlocks(fieldOff, fieldLen)) {
                    val lo = maxOf(fieldOff, addr) - addr
                    val hi = minOf(fieldOff + fieldLen, addr + BLOCK) - addr
                    if (hi > lo) {
                        val block = blocks[addr]!!
                        for (i in lo until hi) raw[rawPos++] = block[i]
                    }
                }
                main.post { onRead(raw) }
            } catch (e: Exception) {
                runCatching { uninstallNotify() }
                runCatching { unwireEventCallback() }
                main.post { onError("Read field failed: ${e.message}") }
            }
        }, "cfg-field-read").start()
    }

    /**
     * Write a single field: read the affected blocks, splice the new bytes,
     * write them back with checksum + ACK (mirror of Python upload_field).
     */
    fun uploadField(
        fieldOff: Int,
        fieldBytes: ByteArray,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
    ) {
        Thread({
            try {
                val pair = resolve() ?: throw RuntimeException("FF00 clone service not found")
                val write = pair.first; val notify = pair.second
                installNotify(notify)
                wireEventCallback()
                enterCloneMode(write)
                val addrs = fieldBlocks(fieldOff, fieldBytes.size)
                val blocks = LinkedHashMap<Int, ByteArray>()
                for (addr in addrs) {
                    writeGatt(write, byteArrayOf(0x52,
                        ((addr ushr 8) and 0xFF).toByte(), (addr and 0xFF).toByte(), BLOCK.toByte()))
                    blocks[addr] = readBlock(addr)
                }
                // splice the new bytes
                var remaining = fieldBytes
                var cur = fieldOff
                for (addr in addrs) {
                    val lo = maxOf(cur, addr) - addr
                    val hi = minOf(fieldOff + fieldBytes.size, addr + BLOCK) - addr
                    if (hi > lo) {
                        val take = hi - lo
                        val block = blocks[addr]!!
                        for (i in 0 until take) block[lo + i] = remaining[i]
                        remaining = remaining.copyOfRange(take, remaining.size)
                        cur = addr + hi
                    }
                }
                // write back with checksum
                var idx = 0
                for (addr in addrs) {
                    val payload = blocks[addr]!!
                    val chk = payload.fold(0) { acc, b -> (acc + (b.toInt() and 0xFF)) and 0xFF }
                    val frame = byteArrayOf(
                        0x57, ((addr ushr 8) and 0xFF).toByte(), (addr and 0xFF).toByte(),
                        BLOCK.toByte()) + payload + byteArrayOf(chk.toByte())
                    writeGatt(write, frame)
                    val ack = readExact(1, 5000)
                    if (ack[0] != 0x06.toByte())
                        throw RuntimeException("write ack expected at 0x%04X".format(addr))
                    idx++
                    main.post { onProgress?.invoke(idx, addrs.size) }
                }
                exitCloneMode(write)
                uninstallNotify()
                unwireEventCallback()
                main.post { onDone?.invoke() }
            } catch (e: Exception) {
                runCatching { uninstallNotify() }
                runCatching { unwireEventCallback() }
                main.post { onError?.invoke("Write field failed: ${e.message}") }
            }
        }, "cfg-field-write").start()
    }
}

/** Small companion holder so callbacks can be registered from the service. */
object CfgIoCompanionFlags {
    @Volatile var onCloneModeChanged: ((Boolean) -> Unit)? = null
    @Volatile private var notifySink: ((ByteArray) -> Unit)? = null

    @Synchronized
    fun setNotifySink(sink: (ByteArray) -> Unit) {
        notifySink = sink
    }

    @Synchronized
    fun clearNotifySink() {
        notifySink = null
    }

    @Synchronized
    fun activeNotifySink(): ((ByteArray) -> Unit)? = notifySink
}