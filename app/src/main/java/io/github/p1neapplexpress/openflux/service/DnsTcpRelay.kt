package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.Loopback
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Accepts pdnsd's DNS-over-TCP connections on 127.0.0.1 and carries each one
 * through the OpenFlux SOCKS5 proxy to a public resolver. The app is excluded
 * from its own VPN, so without this pdnsd would ask the resolver directly,
 * outside the tunnel, where DNS is easy to block or spoof.
 */
class DnsTcpRelay(
    private val socksPort: Int,
    private val upstreams: List<InetSocketAddress> = DEFAULT_UPSTREAMS,
    private val connectTimeoutMs: Int = 10_000,
) : Closeable {

    companion object {
        private const val TAG = "DnsTcpRelay"
        private const val IDLE_TIMEOUT_MS = 30_000

        val DEFAULT_UPSTREAMS = listOf(
            InetSocketAddress(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), 53),
            InetSocketAddress(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)), 53),
        )
    }

    private val server = ServerSocket(0, 50, Loopback.IPV4)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, TAG).apply { isDaemon = true } }

    @Volatile
    private var closed = false

    val port: Int get() = server.localPort

    fun start(): DnsTcpRelay = apply { pool.execute(::acceptLoop) }

    override fun close() {
        closed = true
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (!closed) Logx.w(TAG, "accept failed: ${e.message}")
                return
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use {
            val upstream = connectUpstream() ?: return
            upstream.use {
                client.soTimeout = IDLE_TIMEOUT_MS
                upstream.soTimeout = IDLE_TIMEOUT_MS
                val toUpstream = pool.submit { pump(client, upstream) }
                pump(upstream, client)
                runCatching { toUpstream.get(IDLE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) }
            }
        }
    }

    private fun connectUpstream(): Socket? {
        for (target in upstreams) {
            try {
                return Socks5.connect(socksPort, target, connectTimeoutMs)
            } catch (e: IOException) {
                Logx.w(TAG, "DNS via tunnel to $target failed: ${e.message}")
            }
        }
        return null
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
        } catch (_: IOException) {
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }
}
