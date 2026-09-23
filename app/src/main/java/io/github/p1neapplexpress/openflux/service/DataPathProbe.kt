package io.github.p1neapplexpress.openflux.service

import java.io.DataInputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object DataPathProbe {
    private val resolvers = listOf("1.1.1.1", "8.8.8.8")
    private val query = byteArrayOf(
        0x54, 0x5a, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 0x03, 0x63, 0x6f, 0x6d, 0x00,
        0x00, 0x01, 0x00, 0x01,
    )

    fun measure(socksPort: Int, timeoutMs: Int, preferYandex: Boolean = false): Long {
        if (socksPort <= 0 || timeoutMs <= 0) return -1L
        val started = System.nanoTime()
        val deadline = started + timeoutMs.toLong() * 1_000_000L
        if (preferYandex && measureYandex(socksPort, timeoutMs.coerceAtMost(2_500))) {
            return (System.nanoTime() - started) / 1_000_000L
        }
        for ((index, resolver) in resolvers.withIndex()) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remaining <= 0) break
            val resolverTimeout = remaining.coerceAtMost(if (index == 0) 1_500 else 1_000).coerceAtLeast(1)
            val ok = runCatching {
                Socks5.connect(socksPort, InetSocketAddress(resolver, 53), resolverTimeout).use { socket ->
                    socket.soTimeout = resolverTimeout
                    val output = socket.getOutputStream()
                    output.write(byteArrayOf(0, query.size.toByte()))
                    output.write(query)
                    output.flush()
                    val input = DataInputStream(socket.getInputStream())
                    val length = input.readUnsignedShort()
                    if (length !in 12..4096) return@use false
                    val answer = ByteArray(length)
                    input.readFully(answer)
                    answer[0] == query[0] && answer[1] == query[1] &&
                        (answer[2].toInt() and 0x80) != 0 &&
                        (answer[3].toInt() and 0x0f) == 0 &&
                        (((answer[6].toInt() and 0xff) shl 8) or (answer[7].toInt() and 0xff)) > 0
                }
            }.getOrDefault(false)
            if (ok) return (System.nanoTime() - started) / 1_000_000L
        }
        val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
        if (!preferYandex && remaining > 0 && measureYandex(socksPort, remaining.coerceAtMost(3_000))) {
            return (System.nanoTime() - started) / 1_000_000L
        }
        return -1L
    }

    private fun measureYandex(socksPort: Int, timeoutMs: Int): Boolean = runCatching {
        val host = "disk.yandex.ru"
        Socks5.connect(socksPort, host, 443, timeoutMs).use { proxy ->
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(proxy, host, 443, true).use { socket ->
                val tls = socket as SSLSocket
                tls.soTimeout = timeoutMs
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                true
            }
        }
    }.getOrDefault(false)
}
