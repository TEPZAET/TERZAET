package io.github.p1neapplexpress.openflux.service

import android.os.SystemClock
import io.github.p1neapplexpress.openflux.util.Logx
import java.io.Closeable
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HysteriaUdpRelay(private val localPort: Int) : Closeable {
    private val active = AtomicBoolean(false)
    private val flows = ConcurrentHashMap<InetSocketAddress, Flow>()
    private val workers = Executors.newFixedThreadPool(4)
    @Volatile private var localSocket: DatagramSocket? = null
    @Volatile private var receiver: Thread? = null

    fun start(): Boolean = runCatching {
        val socket = DatagramSocket(null)
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        localSocket = socket
        openFlow(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1)).close()
        active.set(true)
        receiver = Thread(::receiveLoop, "TerzaetHy2UdpRelay").apply { isDaemon = true; start() }
        true
    }.getOrElse {
        close()
        Logx.e("HysteriaUdpRelay", "SOCKS5 UDP association failed: ${it.message}")
        false
    }

    private fun receiveLoop() {
        val buffer = ByteArray(65_535)
        while (active.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                localSocket?.receive(packet) ?: break
                val source = InetSocketAddress(packet.address, packet.port)
                val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                workers.execute {
                    runCatching {
                        val flow = getFlow(source)
                        flow.lastActivity = SystemClock.elapsedRealtime()
                        flow.send(data)
                    }.onFailure { Logx.w("HysteriaUdpRelay", "UDP forward failed: ${it.message}") }
                }
                closeExpiredFlows()
            } catch (error: Exception) {
                if (active.get()) Logx.w("HysteriaUdpRelay", "UDP receive failed: ${error.message}")
            }
        }
    }

    @Synchronized
    private fun getFlow(source: InetSocketAddress): Flow {
        flows[source]?.let { return it }
        check(flows.size < MAX_FLOWS) { "Too many local UDP flows" }
        return openFlow(source).also { flows[source] = it; it.startReceiver() }
    }

    private fun openFlow(source: InetSocketAddress): Flow {
        val control = Socket()
        control.tcpNoDelay = true
        control.soTimeout = CONTROL_TIMEOUT_MS
        control.connect(InetSocketAddress("127.0.0.1", localPort), CONTROL_TIMEOUT_MS)
        val input = control.getInputStream()
        val output = control.getOutputStream()
        output.write(byteArrayOf(5, 1, 0))
        output.flush()
        val hello = readExact(input, 2)
        check(hello[0].toInt() == 5 && hello[1].toInt() == 0) { "SOCKS5 authentication method rejected" }
        output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
        val head = readExact(input, 4)
        check(head[0].toInt() == 5 && head[1].toInt() == 0) { "SOCKS5 UDP ASSOCIATE rejected: ${head[1].toInt() and 0xff}" }
        val relayAddress = when (head[3].toInt() and 0xff) {
            1 -> InetAddress.getByAddress(readExact(input, 4))
            3 -> InetAddress.getByName(String(readExact(input, readExact(input, 1)[0].toInt() and 0xff), Charsets.US_ASCII))
            4 -> InetAddress.getByAddress(readExact(input, 16))
            else -> error("SOCKS5 returned an unknown UDP address type")
        }
        val portBytes = readExact(input, 2)
        val relayPort = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
        check(relayPort in 1..65535) { "SOCKS5 returned an invalid UDP relay port" }
        val resolved = if (relayAddress.isAnyLocalAddress) InetAddress.getByName("127.0.0.1") else relayAddress
        control.soTimeout = 0
        val upstream = DatagramSocket()
        upstream.soTimeout = 1_000
        return Flow(source, control, upstream, InetSocketAddress(resolved, relayPort))
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val output = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(output, offset, count - offset)
            check(read > 0) { "SOCKS5 closed during UDP negotiation" }
            offset += read
        }
        return output
    }

    private fun closeExpiredFlows() {
        val now = SystemClock.elapsedRealtime()
        flows.entries.removeIf { entry ->
            if (now - entry.value.lastActivity > FLOW_IDLE_MS) {
                entry.value.close()
                true
            } else false
        }
    }

    override fun close() {
        if (!active.getAndSet(false) && localSocket == null) return
        runCatching { localSocket?.close() }
        localSocket = null
        receiver?.interrupt()
        receiver = null
        flows.values.forEach(Flow::close)
        flows.clear()
        workers.shutdownNow()
        workers.awaitTermination(1, TimeUnit.SECONDS)
    }

    private inner class Flow(
        private val source: InetSocketAddress,
        private val control: Socket,
        private val upstream: DatagramSocket,
        private val relay: InetSocketAddress,
    ) : Closeable {
        @Volatile var lastActivity: Long = SystemClock.elapsedRealtime()
        private val open = AtomicBoolean(true)

        fun send(data: ByteArray) {
            check(open.get()) { "UDP association is closed" }
            upstream.send(DatagramPacket(data, data.size, relay))
        }

        fun startReceiver() {
            Thread({
                val buffer = ByteArray(65_535)
                while (open.get() && active.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        upstream.receive(packet)
                        lastActivity = SystemClock.elapsedRealtime()
                        localSocket?.send(DatagramPacket(packet.data, packet.offset, packet.length, source))
                    } catch (error: Exception) {
                        if (open.get() && active.get() && error !is java.net.SocketTimeoutException) {
                            Logx.w("HysteriaUdpRelay", "UDP reply failed: ${error.message}")
                        }
                    }
                }
            }, "TerzaetHy2UdpFlow").apply { isDaemon = true; start() }
        }

        override fun close() {
            if (!open.getAndSet(false)) return
            runCatching { upstream.close() }
            runCatching { control.close() }
        }
    }

    companion object {
        private const val CONTROL_TIMEOUT_MS = 5_000
        private const val FLOW_IDLE_MS = 120_000L
        private const val MAX_FLOWS = 256
    }
}
