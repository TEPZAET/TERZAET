package io.github.p1neapplexpress.openflux.util

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Helpers for the 127.0.0.1 listeners shared by the app and its native helpers. */
object Loopback {

    // InetAddress.getLoopbackAddress() is ::1 on Android; the helpers listen on IPv4.
    val IPV4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /** A TCP port that is free right now; the caller hands it to a listener immediately. */
    fun freeTcpPort(): Int = ServerSocket(0, 1, IPV4).use { it.localPort }

    fun canConnect(port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(IPV4, port), timeoutMs) }
        true
    } catch (_: IOException) {
        false
    }
}
