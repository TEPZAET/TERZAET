package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Loopback
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/** Minimal SOCKS5 CONNECT client (no auth, IPv4 targets) for the local OpenFlux proxy. */
object Socks5 {

    fun connect(proxyPort: Int, target: InetSocketAddress, timeoutMs: Int): Socket {
        val ip = (target.address as? Inet4Address)?.address
            ?: throw IOException("SOCKS5 needs an IPv4 target, got $target")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(Loopback.IPV4, proxyPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            val method = ByteArray(2).also(input::readFully)
            if (method[0] != 5.toByte() || method[1] != 0.toByte()) {
                throw IOException("SOCKS5 proxy refused no-auth")
            }

            // A single write: OpenFlux reads the whole request with one Read().
            val port = target.port
            out.write(byteArrayOf(5, 1, 0, 1, ip[0], ip[1], ip[2], ip[3], (port shr 8).toByte(), port.toByte()))
            out.flush()
            val reply = ByteArray(10).also(input::readFully)
            if (reply[1] != 0.toByte()) {
                throw IOException("SOCKS5 connect to $target failed (reply ${reply[1]})")
            }

            socket.soTimeout = 0
            return socket
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }
}
