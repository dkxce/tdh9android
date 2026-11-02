/*
 * KISS framing protocol (encapsulation/decapsulation).
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge.core

import java.io.ByteArrayOutputStream

/**
 * KISS constants and helpers. Mirrors the Python `kiss.py`.
 *
 * FEND marks a frame boundary; FESC escapes FEND and FESC inside the body.
 * Command byte packs port (high nibble) and command type (low nibble).
 */
object Kiss {
    const val FEND: Int = 0xC0
    const val FESC: Int = 0xDB
    const val TFEND: Int = 0xDC
    const val TFESC: Int = 0xDD

    const val CMD_DATA = 0x00
    const val CMD_TXDELAY = 0x01
    const val CMD_PERSIST = 0x02
    const val CMD_SLOTTIME = 0x03
    const val CMD_TXTAIL = 0x04
    const val CMD_FULLDUPLEX = 0x05
    const val CMD_SETHARDWARE = 0x06
    const val CMD_RETURN = 0xFF

    /** KISS-escape a raw payload (AX.25) body. */
    fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (b in data) {
            val u = b.toInt() and 0xFF
            when (u) {
                FEND -> { out.write(FESC); out.write(TFEND) }
                FESC -> { out.write(FESC); out.write(TFESC) }
                else -> out.write(u)
            }
        }
        return out.toByteArray()
    }

    /**
     * Build a full KISS frame: [FEND][cmd][escaped body][FEND].
     * @param port KISS port number (0..15).
     * @param command KISS command byte (default CMD_DATA).
     */
    fun frame(ax25: ByteArray, port: Int = 0, command: Int = CMD_DATA): ByteArray {
        val cmdByte = ((port and 0x0F) shl 4) or (command and 0x0F)
        val body = ByteArrayOutputStream()
        body.write(cmdByte)
        body.write(escape(ax25))
        return byteArrayOf(FEND.toByte()) + body.toByteArray() + byteArrayOf(FEND.toByte())
    }
}

/**
 * Callback for a complete, decapsulated KISS frame.
 * @param port KISS port (high nibble of command byte).
 * @param command KISS command type (low nibble).
 * @param payload the de-escaped AX.25 body bytes.
 */
fun interface KissFrameCallback {
    fun onFrame(port: Int, command: Int, payload: ByteArray)
}

/**
 * Incremental KISS stream parser. Feed bytes from BLE/TCP; handles FESC
 * escapes split across chunks (e.g. live streams).
 */
class KissParser(private val cb: KissFrameCallback) {
    private var inFrame = false
    private val buf = ByteArrayOutputStream()
    private var pendingEscape = false

    fun feed(data: ByteArray) {
        if (data.isEmpty()) return
        for (raw in data) {
            val b = raw.toInt() and 0xFF
            if (pendingEscape) {
                pendingEscape = false
                when (b) {
                    Kiss.TFEND -> buf.write(Kiss.FEND)
                    Kiss.TFESC -> buf.write(Kiss.FESC)
                    // FESC + anything else: malformed, drop both
                }
                continue
            }
            if (!inFrame) {
                if (b == Kiss.FEND) {
                    inFrame = true
                    buf.reset()
                }
                continue
            }
            when (b) {
                Kiss.FEND -> {
                    if (buf.size() > 0) {
                        val frame = buf.toByteArray()
                        val cmd = frame[0].toInt() and 0xFF
                        val port = (cmd shr 4) and 0x0F
                        val command = cmd and 0x0F
                        val payload = frame.copyOfRange(1, frame.size)
                        try { cb.onFrame(port, command, payload) } catch (_: Exception) { }
                    }
                    inFrame = false
                    buf.reset()
                }
                Kiss.FESC -> pendingEscape = true
                else -> buf.write(b)
            }
        }
    }
}