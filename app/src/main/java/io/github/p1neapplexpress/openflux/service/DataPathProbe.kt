package io.github.p1neapplexpress.openflux.service

import java.io.DataInputStream
import java.net.InetSocketAddress

object DataPathProbe {
    private val resolvers = listOf("1.1.1.1", "8.8.8.8")
    private val query = byteArrayOf(
        0x54, 0x5a, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 0x03, 0x63, 0x6f, 0x6d, 0x00,
        0x00, 0x01, 0x00, 0x01,
    )

    fun measure(socksPort: Int, timeoutMs: Int): Long {
        if (socksPort <= 0) return -1L
        val started = System.nanoTime()
        for ((index, resolver) in resolvers.withIndex()) {
            val resolverTimeout = if (index == 0) timeoutMs.coerceAtMost(3_000) else timeoutMs
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
        return -1L
    }
}
