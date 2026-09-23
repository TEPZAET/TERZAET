package io.github.p1neapplexpress.openflux.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class DataPathProbeTest {
    @Test
    fun `requires a DNS response after SOCKS connect`() {
        ServerSocket(0).use { server ->
            val worker = thread {
                repeat(2) {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        input.readFully(ByteArray(3))
                        output.write(byteArrayOf(5, 0))
                        input.readFully(ByteArray(10))
                        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                        input.readUnsignedShort().let { input.readFully(ByteArray(it)) }
                    }
                }
            }
            assertEquals(-1L, DataPathProbe.measure(server.localPort, 500))
            worker.join(2_000)
        }
    }

    @Test
    fun `accepts a complete DNS answer`() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    input.readFully(ByteArray(3))
                    output.write(byteArrayOf(5, 0))
                    input.readFully(ByteArray(10))
                    output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                    val answer = query.copyOf(query.size + 16)
                    answer[2] = 0x81.toByte()
                    answer[3] = 0x80.toByte()
                    answer[6] = 0
                    answer[7] = 1
                    val offset = query.size
                    val record = byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184.toByte(), 215.toByte(), 14)
                    record.copyInto(answer, offset)
                    output.write(byteArrayOf((answer.size shr 8).toByte(), answer.size.toByte()))
                    output.write(answer)
                    output.flush()
                }
            }
            assertTrue(DataPathProbe.measure(server.localPort, 500) >= 0L)
            worker.join(2_000)
        }
    }
}
