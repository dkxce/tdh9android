/*
 * AGWPE TCP/IP server (SV2AGW / Dire Wolf compatible, 36-byte framing).
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge.net

import com.dkxce.radio_bridge.core.Ax25
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalTime

/**
 * AGWPE TCP/IP compatible engine. Clients connect (UISS, UI-View32, APRSIS32,
 * AGW clients) and communicate with 36-byte headers, format matches Dire Wolf.
 *
 * Client -> Server kinds: 'R' version, 'G'/'g' port info/caps, 'X' register,
 * 'm'/'k' monitor/raw toggles, 'M'/'V'/'K' transmit.
 * Server -> Client: 'R' version reply, 'G'/'g' replies, 'X' ack, 'U' monitor.
 */
class AgwTcpServer(
    port: Int,
    private val onTx: (ByteArray) -> Unit,
    onClients: () -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : BaseTcpServer(port, onClients), FrameBroadcaster {

    private data class ClientState(val c: TcpClient, var monitor: Boolean = false)

    // AGW header format: BxxxBxBx10s10sIxxxx (36 bytes)
    // port(1) res(3) kind(1) res(1) pid(1) res(1) from(10) to(10) len(4 LE) res4
    private val headerSize = 36

    private val KIND_VERSION = 'R'.code
    private val KIND_PORT_INFO = 'G'.code
    private val KIND_PORT_CAPS = 'g'.code
    private val KIND_REGISTER = 'X'.code
    private val KIND_MONITOR = 'm'.code
    private val KIND_RAW = 'k'.code
    private val KIND_SEND_M = 'M'.code
    private val KIND_SEND_V = 'V'.code
    private val KIND_SEND_K = 'K'.code

    override fun handleClient(c: TcpClient) {
        roles.putIfAbsent(c, ClientState(c))
        var buf = ByteArray(0)
        try {
            val input: InputStream = c.socket.getInputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n > 0) {
                    buf = buf + chunk.copyOfRange(0, n)
                    buf = processBuffer(c, buf)
                }
            }
        } catch (_: IOException) {
        } catch (_: Exception) {
        } finally {
            roles.remove(c)
            removeClient(c)
        }
    }

    private var roles = java.util.concurrent.ConcurrentHashMap<TcpClient, ClientState>()

    /** Consume complete frames from the buffer, return the remainder. */
    private fun processBuffer(c: TcpClient, buf: ByteArray): ByteArray {
        var off = 0
        while (buf.size - off >= headerSize) {
            val len = ByteBuffer.wrap(buf, off + 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val total = headerSize + len
            if (buf.size - off < total) break
            val frame = buf.copyOfRange(off, off + total)
            handleFrame(c, frame)
            off += total
        }
        return if (off > 0) buf.copyOfRange(off, buf.size) else buf
    }

    private fun parseHeader(frame: ByteArray): AgwHeader {
        val kind = frame[4].toInt()
        val pid = frame[6].toInt()
        val from = String(frame, 8, 10, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
        val to = String(frame, 18, 10, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
        val len = ByteBuffer.wrap(frame, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val data = frame.copyOfRange(headerSize, headerSize + len)
        return AgwHeader(kind, pid, from, to, data)
    }

    private fun handleFrame(c: TcpClient, frame: ByteArray) {
        val h = parseHeader(frame)
        when (h.kind) {
            KIND_VERSION -> {
                sendFrame(c, 'R'.code, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                    .putInt(2005).putInt(127).array())
            }
            KIND_PORT_INFO -> sendFrame(c, 'G'.code, "0;TNC1;".toByteArray())
            KIND_PORT_CAPS -> sendFrame(c, 'g'.code, portCaps())
            KIND_REGISTER -> sendFrame(c, 'X'.code, byteArrayOf(1))
            KIND_MONITOR -> roles[c]?.let { it.monitor = !it.monitor }
            KIND_RAW -> roles[c]?.let { it.monitor = !it.monitor } // toggle raw
            KIND_SEND_M, KIND_SEND_V, KIND_SEND_K -> handleTx(h)
        }
    }

    private fun handleTx(h: AgwHeader) {
        var payload: ByteArray? = null
        when (h.kind) {
            'K'.code -> if (h.data.size >= 2) payload = h.data.copyOfRange(1, h.data.size)
            'M'.code -> {
                val info = String(h.data, Charsets.ISO_8859_1)
                val text = "${h.from}>${h.to}:$info"
                payload = try { Ax25.encodeText(text) } catch (_: Exception) { h.data }
            }
            'V'.code -> if (h.data.isNotEmpty()) {
                val ndigi = h.data[0].toInt() and 0xFF
                val pos = 1 + ndigi * 10
                val digis = ArrayList<String>()
                for (j in 0 until ndigi) {
                    val d = String(h.data, 1 + j * 10, 10, Charsets.ISO_8859_1)
                        .trimEnd('\u0000', ' ').trim()
                    if (d.isNotEmpty()) digis.add(d)
                }
                val info = if (pos < h.data.size) String(h.data, pos, h.data.size - pos, Charsets.ISO_8859_1) else ""
                val path = if (digis.isNotEmpty()) "," + digis.joinToString(",") else ""
                val text = "${h.from}>${h.to}$path:$info"
                payload = try { Ax25.encodeText(text) } catch (_: Exception) { h.data }
            }
        }
        payload?.let { onTx(it) }
    }

    private fun sendFrame(c: TcpClient, kind: Int, data: ByteArray) {
        val hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put(0)        // port
        hdr.put(0)        // res
        hdr.put(0)
        hdr.put(0)
        hdr.put(kind.toByte())
        hdr.put(0)
        hdr.put(0xF0.toByte()) // pid
        hdr.put(0)
        for (i in 0 until 10) hdr.put(0)     // call_from padding
        for (i in 0 until 10) hdr.put(0)     // call_to padding
        hdr.putInt(data.size)
        hdr.put(0); hdr.put(0); hdr.put(0); hdr.put(0)
        try {
            val out: OutputStream = c.socket.getOutputStream()
            out.write(hdr.array())
            out.write(data)
            out.flush()
        } catch (_: Exception) { }
    }

    private fun portCaps(): ByteArray {
        // 8 bytes baud/traffic/txdelay/txtail/persist/slot/maxframe/conn + uint32
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.put(3)   // baud code 1200
        b.put(0)   // traffic
        b.put(50)  // txdelay
        b.put(1)   // txtail
        b.put(64)  // persist
        b.put(50)  // slot
        b.put(230) // maxframe
        b.put(0)   // conns
        b.putInt(0)
        return b.array()
    }

    override fun broadcastRadio(raw: ByteArray) {
        // Build Dire Wolf style 'U' monitor frame for clients with monitor ON.
        val now = LocalTime.now().toString().substring(0, 8) // HH:MM:SS
        val fr = Ax25.decode(raw)
        var cfrom = ""; var cto = "APRS"; var pidHex = "F0"; var info = raw
        if (fr != null && fr.source.isNotEmpty()) {
            cfrom = fr.source; cto = fr.destination
            pidHex = if (fr.pid != null) String.format("%02X", fr.pid) else "F0"
            info = fr.info
        }
        val via = if (fr != null && fr.repeaters.isNotEmpty()) "Via ${fr.repeaters.joinToString(",")} " else ""
        val infoText = String(info, Charsets.ISO_8859_1)
        val infoBytes = infoText.toByteArray(Charsets.ISO_8859_1)
        val len = infoBytes.size + 1
        val preamble = " 1:Fm $cfrom To $cto $via<UI pid=$pidHex Len=$len >[$now]\r".toByteArray()
        val payload = preamble + infoBytes + byteArrayOf('\r'.code.toByte())

        for ((c, st) in roles) {
            if (!st.monitor) continue
            try {
                val out: OutputStream = c.socket.getOutputStream()
                val hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
                hdr.put(0); hdr.put(0); hdr.put(0); hdr.put(0)
                hdr.put('U'.code.toByte()) // monitor unproto
                hdr.put(0)
                hdr.put(0xF0.toByte())
                hdr.put(0)
                for (i in 0 until 10) hdr.put(0)
                for (i in 0 until 10) hdr.put(0)
                hdr.putInt(payload.size)
                hdr.put(0); hdr.put(0); hdr.put(0); hdr.put(0)
                out.write(hdr.array())
                out.write(payload)
                out.flush()
            } catch (_: Exception) { }
        }
    }

    private data class AgwHeader(val kind: Int, val pid: Int, val from: String, val to: String, val data: ByteArray)
}