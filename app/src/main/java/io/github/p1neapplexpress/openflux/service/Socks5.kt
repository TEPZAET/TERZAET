package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Loopback
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object Socks5 {

    fun connect(proxyPort: Int, host: String, port: Int, timeoutMs: Int): Socket {
        if (port !in 1..65535) throw IOException("Invalid SOCKS5 port")
        val hostBytes = host.toByteArray(StandardCharsets.US_ASCII)
        if (hostBytes.isEmpty() || hostBytes.size > 255 || !host.all { it.code in 33..126 }) {
            throw IOException("Invalid SOCKS5 host")
        }
        return connectRequest(proxyPort, timeoutMs, byteArrayOf(5, 1, 0, 3, hostBytes.size.toByte()) +
            hostBytes + byteArrayOf((port shr 8).toByte(), port.toByte()))
    }

    fun connect(proxyPort: Int, target: InetSocketAddress, timeoutMs: Int): Socket {
        val ip = (target.address as? Inet4Address)?.address
            ?: throw IOException("SOCKS5 needs an IPv4 target, got $target")
        val port = target.port
        return connectRequest(proxyPort, timeoutMs, byteArrayOf(5, 1, 0, 1, ip[0], ip[1], ip[2], ip[3], (port shr 8).toByte(), port.toByte()))
    }

    private fun connectRequest(proxyPort: Int, timeoutMs: Int, request: ByteArray): Socket {
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

            out.write(request)
            out.flush()
            val header = ByteArray(4).also(input::readFully)
            if (header[0] != 5.toByte() || header[1] != 0.toByte()) {
                throw IOException("SOCKS5 connect failed (reply ${header[1]})")
            }
            val addressLength = when (header[3].toInt() and 0xff) {
                1 -> 4
                3 -> input.readUnsignedByte()
                4 -> 16
                else -> throw IOException("Invalid SOCKS5 reply address")
            }
            ByteArray(addressLength + 2).also(input::readFully)

            socket.soTimeout = 0
            return socket
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }
}
