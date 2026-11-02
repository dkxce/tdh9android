/*
 ==============================================================================
  Common TCP server plumbing (accept loop, connection set, broadcast)
  Author:  dkxce (c)  https://github.com/dkxce/Tidradio-TD-H9
  Version: v1.1.0

  English:  Base class for the local TCP servers (Binary KISS / AGWPE /
            APRS-IS). Hosts a listen socket, manages a client set and offers
            a per-protocol client list and radio broadcast. Mirrors the Python
            reference server architecture.
  Russian:  Базовый класс локальных TCP-серверов (KISS/AGW/APRS-IS).
 ==============================================================================
 */

package com.dkxce.radio_bridge.net

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One connected client with metadata. */
class TcpClient(val socket: Socket) {
    val address: String get() = socket.inetAddress?.hostAddress ?: "?"
    val port: Int get() = socket.port
    /** Optional protocol-specific metadata (APRS-IS callsign / verified). */
    @Volatile var callsign: String = ""
    @Volatile var verified: Boolean = false
}

/**
 * Base TCP server hosting a listen socket and managing a set of clients.
 * Subclasses override [handleClient] to run the per-connection protocol.
 */
abstract class BaseTcpServer(
    val port: Int,
    private val onClientsChange: () -> Unit = {},
    private val protocol: String = "",   // "kiss" / "agw" / "aprs"
) {
    private var serverSocket: ServerSocket? = null
    private val acceptThread = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    protected val clients = CopyOnWriteArrayList<TcpClient>()

    /** Called on the main thread? No - called from accept loop thread. */
    fun start() {
        if (acceptThread.getAndSet(true)) return
        try {
            val ss = ServerSocket(port)
            ss.reuseAddress = true
            serverSocket = ss
            executor.execute(::acceptLoop)
        } catch (e: IOException) {
            acceptThread.set(false)
            onError("Listen failed on $port: ${e.message}")
        }
    }

    fun stop() {
        acceptThread.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        for (c in clients) {
            try { c.socket.close() } catch (_: Exception) { }
        }
        clients.clear()
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (acceptThread.get()) {
            try {
                val s = ss.accept()
                val client = TcpClient(s)
                clients.add(client)
                onClientsChange()
                executor.execute { handleClient(client) }
            } catch (e: IOException) {
                if (acceptThread.get()) onError("Accept error: ${e.message}")
            }
        }
    }

    protected fun removeClient(c: TcpClient) {
        clients.remove(c)
        try { c.socket.close() } catch (_: Exception) { }
        onClientsChange()
    }

    /** Let subclasses signal that client metadata changed (e.g. APRS login). */
    protected fun notifyClientsChanged() {
        onClientsChange()
    }

    val clientCount: Int get() = clients.size

    /** List of connected clients as "protocol://ip:port/tx". APRS-IS overrides this
     * to reflect verified ("/tx") / unverified ("/rx") state. */
    open fun clientList(): List<String> {
        return clients.map { c ->
            "$protocol://${c.address}:${c.port}/tx"
        }
    }

    protected fun onError(msg: String) {}

    /** Per-connection protocol loop. When it returns, the client is removed. */
    protected abstract fun handleClient(c: TcpClient)
}

/**
 * Turns received radio frames into per-protocol bytes and forwards them to
 * clients. Implemented by each server.
 */
interface FrameBroadcaster {
    /** Called with a raw AX.25/APRS payload received from the radio. */
    fun broadcastRadio(raw: ByteArray)
}