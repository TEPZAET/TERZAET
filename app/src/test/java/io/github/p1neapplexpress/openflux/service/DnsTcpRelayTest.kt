package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Loopback
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class DnsTcpRelayTest {

    /**
     * Stand-in for the OpenFlux SOCKS5 server: records CONNECT targets, refuses
     * the IPs in [refuse] and echoes everything else back after connecting.
     */
    private class FakeSocks(private val refuse: Set<String> = emptySet()) : Closeable {
        val server = ServerSocket(0, 50, Loopback.IPV4)
        val targets = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) { handle(socket) }
                }
            }
        }

        private fun handle(socket: Socket) = socket.use {
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            input.readFully(ByteArray(3))
            out.write(byteArrayOf(5, 0))
            val request = ByteArray(10).also(input::readFully)
            val ip = request.sliceArray(4..7).joinToString(".") { (it.toInt() and 0xff).toString() }
            val port = ((request[8].toInt() and 0xff) shl 8) or (request[9].toInt() and 0xff)
            targets += "$ip:$port"
            if (ip in refuse) {
                out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                return@use
            }
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            input.copyTo(out)
        }

        override fun close() = server.close()
    }

    private fun roundTrip(relay: DnsTcpRelay, payload: ByteArray): ByteArray =
        Socket(Loopback.IPV4, relay.port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(payload)
            ByteArray(payload.size).also(DataInputStream(client.getInputStream())::readFully)
        }

    @Test
    fun `forwards a connection through socks to the first resolver`() {
        FakeSocks().use { socks ->
            DnsTcpRelay(socks.server.localPort).start().use { relay ->
                val query = byteArrayOf(0, 4, 0x12, 0x34, 0x01, 0x00)
                assertArrayEquals(query, roundTrip(relay, query))
            }
            assertEquals(listOf("1.1.1.1:53"), socks.targets)
        }
    }

    @Test
    fun `falls back to the next resolver when socks refuses`() {
        FakeSocks(refuse = setOf("1.1.1.1")).use { socks ->
            DnsTcpRelay(socks.server.localPort).start().use { relay ->
                val query = byteArrayOf(0, 2, 0x56, 0x78)
                assertArrayEquals(query, roundTrip(relay, query))
            }
            assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53"), socks.targets)
        }
    }
}
