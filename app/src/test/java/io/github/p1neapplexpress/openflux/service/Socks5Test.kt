package io.github.p1neapplexpress.openflux.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class Socks5Test {
    @Test
    fun `sends hostname through proxy and accepts domain reply`() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    assertEquals(listOf<Byte>(5, 1, 0), ByteArray(3).also(input::readFully).toList())
                    output.write(byteArrayOf(5, 0))
                    val header = ByteArray(5).also(input::readFully)
                    assertEquals(3, header[3].toInt())
                    val hostname = ByteArray(header[4].toInt()).also(input::readFully)
                    assertEquals("disk.yandex.ru", String(hostname))
                    assertEquals(listOf<Byte>(1, -69), ByteArray(2).also(input::readFully).toList())
                    output.write(byteArrayOf(5, 0, 0, 3, 2, 111, 107, 0, 80))
                    output.flush()
                }
            }
            Socks5.connect(server.localPort, "disk.yandex.ru", 443, 1_000).use { }
            worker.join(2_000)
        }
    }
}
