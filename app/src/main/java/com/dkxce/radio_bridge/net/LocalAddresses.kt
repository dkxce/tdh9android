/*
 * Network address helper: lists local IPv4 addresses of the device.
 * Author: dkxce (c) https://github.com/dkxce/Tidradio-TD-H9
 * Version: v1.0.0
 */

package com.dkxce.radio_bridge.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Enumerates local (non-loopback) IPv4 addresses, so a client on the same
 * Wi-Fi/LAN can reach the server. Works without extra permissions.
 */
object LocalAddresses {

    /** Returns a list of IPv4 addresses (e.g. "192.168.1.5"). */
    fun ipv4(): List<String> {
        val out = ArrayList<String>()
        try {
            val nics = NetworkInterface.getNetworkInterfaces()
            while (nics.hasMoreElements()) {
                val nic = nics.nextElement()
                if (!nic.isUp) continue
                if (nic.isLoopback) continue
                val addrs = nic.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        out.add(a.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) { }
        return out
    }
}