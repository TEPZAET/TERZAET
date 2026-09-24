package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Loopback
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class HysteriaUdpRelayTest {
    @Test
    fun `keeps UDP on a separate port and forwards through SOCKS association`() {
        DatagramSocket(0, Loopback.IPV4).use { upstream ->
            ServerSocket(0, 50, Loopback.IPV4).use { socks ->
                val udpWorker = thread(isDaemon = true) {
                    val buffer = ByteArray(2048)
                    while (!upstream.isClosed) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            upstream.receive(packet)
                            upstream.send(DatagramPacket(packet.data, packet.length, packet.address, packet.port))
                        } catch (_: IOException) {
                            break
                        }
                    }
                }
                val socksWorker = thread(isDaemon = true) {
                    while (!socks.isClosed) {
                        val client = try { socks.accept() } catch (_: IOException) { break }
                        thread(isDaemon = true) { serveAssociation(client, upstream.localPort) }
                    }
                }
                HysteriaUdpRelay(socks.localPort).use { relay ->
                    assertTrue(relay.start())
                    assertNotEquals(socks.localPort, relay.port)
                    DatagramSocket(0, Loopback.IPV4).use { client ->
                        client.soTimeout = 3_000
                        val payload = byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53, 1, 2, 3)
                        client.send(DatagramPacket(payload, payload.size, Loopback.IPV4, relay.port))
                        val answer = DatagramPacket(ByteArray(2048), 2048)
                        client.receive(answer)
                        assertArrayEquals(payload, answer.data.copyOf(answer.length))
                    }
                }
                upstream.close()
                socks.close()
                udpWorker.join(1_000)
                socksWorker.join(1_000)
            }
        }
    }

    private fun serveAssociation(socket: Socket, udpPort: Int) = socket.use { client ->
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        input.readFully(ByteArray(3))
        output.write(byteArrayOf(5, 0))
        input.readFully(ByteArray(10))
        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (udpPort shr 8).toByte(), udpPort.toByte()))
        output.flush()
        while (input.read() >= 0) Unit
    }
}
