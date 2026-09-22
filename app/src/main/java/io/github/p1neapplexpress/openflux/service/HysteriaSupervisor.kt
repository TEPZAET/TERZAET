package io.github.p1neapplexpress.openflux.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.Loopback
import java.io.File
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HysteriaSupervisor(
    private val context: Context,
    private val protectSocket: (Int) -> Boolean,
    private val onUnexpectedExit: (String) -> Unit,
) {
    companion object {
        const val NATIVE_LIB = "libhysteria.so"
        private const val READY_TIMEOUT_MS = 20_000L
        private const val STOP_GRACE_MS = 1_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val controller = HysteriaFdController(protectSocket) { message ->
        EventBus.dispatch(AppEvent.LogMessage(message))
    }
    private var process: Process? = null
    private var lastOutput: String? = null

    @Volatile
    var socksPort: Int = 0
        private set

    @Volatile
    var error: String? = null
        private set

    val isReady: Boolean get() = ready.get()

    fun start(uri: String) {
        if (running.getAndSet(true)) return
        stopping.set(false)
        ready.set(false)
        error = null
        lastOutput = null
        try {
            val parsed = URI(uri)
            if (!parsed.scheme.equals("hysteria2", true) || parsed.host.isNullOrBlank()) {
                error("Некорректная ссылка Hysteria 2")
                return
            }
            val nativeDir = context.applicationInfo.nativeLibraryDir
            StaleProcesses.kill(nativeDir)
            socksPort = Loopback.freeTcpPort()
            val config = File(context.noBackupFilesDir, "hysteria-client.yaml")
            val fdSocket = File(context.filesDir, "hysteria_fd.sock")
            if (!controller.start(fdSocket)) {
                error("Не удалось подготовить защиту Hysteria от VPN-петли")
                return
            }
            config.writeText(buildConfig(uri, socksPort, fdSocket.absolutePath))
            val binary = "$nativeDir/$NATIVE_LIB"
            val p = ProcessBuilder(binary, "-c", config.absolutePath)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            process = p
            val output = thread(name = "HysteriaOutput", isDaemon = true) { pumpOutput(p) }
            thread(name = "HysteriaWatch", isDaemon = true) { watch(p, output) }
        } catch (e: Exception) {
            error("Не удалось запустить Hysteria 2: ${e.message}")
        }
    }

    fun stop() {
        stopping.set(true)
        ready.set(false)
        running.set(false)
        controller.stop()
        process?.let { process ->
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
        process = null
        File(context.filesDir, "hysteria_fd.sock").delete()
        File(context.noBackupFilesDir, "hysteria-client.yaml").delete()
    }

    fun measureDataPathLatency(timeoutMs: Int = 5_000): Long {
        if (!ready.get() || socksPort <= 0) return -1L
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
                socket.soTimeout = timeoutMs
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                output.write(byteArrayOf(5, 1, 0))
                output.flush()
                if (input.read() != 5 || input.read() != 0) return@runCatching -1L
                output.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 1, 0xBB.toByte()))
                output.flush()
                val response = ByteArray(4)
                var offset = 0
                while (offset < response.size) {
                    val count = input.read(response, offset, response.size - offset)
                    if (count < 0) return@runCatching -1L
                    offset += count
                }
                if (response[0].toInt() == 5 && response[1].toInt() == 0) {
                    SystemClock.elapsedRealtime() - startedAt
                } else {
                    -1L
                }
            }
        }.getOrDefault(-1L)
    }

    private fun watch(process: Process, output: Thread) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (!stopping.get() && process.isAlive) {
            if (Loopback.canConnect(socksPort, 200)) {
                ready.set(true)
                EventBus.dispatch(AppEvent.TransportConnected)
                break
            }
            if (System.currentTimeMillis() > deadline) {
                fail("Hysteria 2 не открыла локальный канал")
                process.destroyForcibly()
                return
            }
            Thread.sleep(200L)
        }
        val code = process.waitFor()
        if (stopping.get() || this.process !== process) return
        output.join(STOP_GRACE_MS)
        fail("Hysteria 2 завершилась с кодом $code: ${lastOutput ?: "без сообщения"}")
    }

    private fun pumpOutput(process: Process) {
        runCatching {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    lastOutput = line
                    EventBus.dispatch(AppEvent.LogMessage(line))
                }
            }
        }
    }

    private fun fail(message: String) {
        error = message
        ready.set(false)
        running.set(false)
        controller.stop()
        if (!stopping.get()) handler.post { onUnexpectedExit(message) }
    }

    private fun error(message: String) {
        Logx.e("HysteriaSupervisor", message)
        fail(message)
    }

    private fun buildConfig(uri: String, port: Int, socket: String) = """
        server: ${yaml(uri)}
        socks5:
          listen: "127.0.0.1:$port"
        quic:
          keepAlivePeriod: 10s
          sockopts:
            fdControlUnixSocket: ${yaml(socket)}
        lazy: true
    """.trimIndent()

    private fun yaml(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
