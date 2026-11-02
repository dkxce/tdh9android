/*
 ==============================================================================
  Binary KISS over TCP server
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  Presents the radio as a raw KISS TNC over TCP (binary framing),
            exactly like a serial TNC over the network. Clients send KISS
            frames to TX and receive radio frames wrapped into KISS frames.
            Mirrors the Python reference kiss_tcp_server.
  Russian:  KISS TNC поверх TCP/IP (сырые KISS-фреймы, порт по умолчанию 8023).
 ==============================================================================
 */

package com.dkxce.radio_bridge.net

import com.dkxce.radio_bridge.core.Kiss
import com.dkxce.radio_bridge.core.KissFrameCallback
import com.dkxce.radio_bridge.core.KissParser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Presents the radio as a raw KISS TNC over TCP (binary framing), exactly
 * like a serial TNC over the network. Clients send KISS frames to TX and
 * receive KISS frames wrapped radio packets.
 *
 * The outbound callback [onTx] receives the decapsulated AX.25 payload to
 * transmit over BLE.
 */
class KissTcpServer(
    port: Int,
    private val onTx: (ByteArray) -> Unit,
    onClients: () -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : BaseTcpServer(port, onClients, "kiss"), FrameBroadcaster {

    override fun handleClient(c: TcpClient) {
        val parser = KissParser(KissFrameCallback { port, command, payload ->
            if (command == Kiss.CMD_DATA && payload.isNotEmpty()) onTx(payload)
        })
        try {
            val input: InputStream = c.socket.getInputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                parser.feed(buf.copyOfRange(0, n))
            }
        } catch (_: IOException) {
        } catch (_: Exception) {
        } finally {
            removeClient(c)
        }
    }

    override fun broadcastRadio(raw: ByteArray) {
        val frame = Kiss.frame(raw)
        val targets = clients.toList()
        for (c in targets) {
            try {
                val out: OutputStream = c.socket.getOutputStream()
                out.write(frame)
                out.flush()
            } catch (e: Exception) {
                onLog?.invoke("[KISS!] broadcast to ${c.address} failed: $e")
            }
        }
    }
}