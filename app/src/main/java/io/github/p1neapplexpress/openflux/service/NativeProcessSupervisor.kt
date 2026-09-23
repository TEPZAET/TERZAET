package io.github.p1neapplexpress.openflux.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.p1neapplexpress.openflux.data.EncryptionKey
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.Loopback
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the OpenFlux client binary: builds its command line, waits until its
 * SOCKS5 port accepts connections and reports an unexpected exit through
 * [onUnexpectedExit] (called on the main thread).
 */
class NativeProcessSupervisor(
    private val context: Context,
    private val onUnexpectedExit: (String) -> Unit,
) {

    companion object {
        private const val TAG = "NativeProcSupervisor"
        const val NATIVE_LIB = "libp1npplydtransport.so"

        // cups.online joins its rooms before the SOCKS5 server starts listening.
        private const val READY_TIMEOUT_MS = 45_000L
        private const val READY_POLL_MS = 250L
        private const val CONNECT_PROBE_MS = 200
        private const val STOP_GRACE_MS = 1_000L
        private const val KEY_FILE = "openflux-encryption.key"

        // Go's log prefix: "2026/09/17 01:02:03.456789 main.go:349: ".
        private val LOG_PREFIX = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)? (\S+\.go:\d+: )?""")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var lastOutput: String? = null

    /** SOCKS5 port of the current run on 127.0.0.1. */
    @Volatile
    var socksPort: Int = 0
        private set

    /** Why the last run failed, or null. */
    @Volatile
    var error: String? = null
        private set

    val isReady: Boolean get() = ready.get()

    private val keyFile: File get() = File(context.noBackupFilesDir, KEY_FILE)

    fun start(payload: List<String>, encryptionKey: String?) {
        if (running.getAndSet(true)) {
            Logx.d(TAG, "already running, ignoring start")
            return
        }
        shuttingDown.set(false)
        ready.set(false)
        error = null
        lastOutput = null

        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            StaleProcesses.kill(nativeDir)

            socksPort = Loopback.freeTcpPort()
            val keyPath = encryptionKey?.let(::writeKey)
            val args = NativeArgs.build(payload, "127.0.0.1:$socksPort", keyPath)
            Logx.i(TAG, "exec: $NATIVE_LIB ${NativeArgs.redact(args).joinToString(" ")}")

            val p = ProcessBuilder(listOf("$nativeDir/$NATIVE_LIB") + args)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            process = p
            val output = thread(name = "OpenFluxOutput", isDaemon = true) { pumpOutput(p) }
            thread(name = "OpenFluxWatch", isDaemon = true) { watch(p, output) }
        } catch (e: Exception) {
            Logx.e(TAG, "spawn failed", e)
            fail("Failed to start OpenFlux: ${e.message}")
        }
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        shuttingDown.set(true)
        ready.set(false)
        running.set(false)
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
        process = null
        deleteKey()
    }

    fun measureDataPathLatency(timeoutMs: Int = 5_000): Long {
        if (!ready.get() || socksPort <= 0) return -1L
        return DataPathProbe.measure(socksPort, timeoutMs)
    }

    private fun watch(p: Process, output: Thread) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (!shuttingDown.get() && p.isAlive) {
            if (Loopback.canConnect(socksPort, CONNECT_PROBE_MS)) {
                ready.set(true)
                // OpenFlux reads the key before it starts listening.
                deleteKey()
                Logx.i(TAG, "OpenFlux is up, SOCKS5 on 127.0.0.1:$socksPort")
                EventBus.dispatch(AppEvent.TransportConnected)
                break
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                fail("OpenFlux did not start within ${READY_TIMEOUT_MS / 1000} s")
                destroy(p)
                return
            }
            Thread.sleep(READY_POLL_MS)
        }

        val code = p.waitFor()
        if (shuttingDown.get() || process !== p) return
        output.join(STOP_GRACE_MS) // let the reader catch the fatal log line
        val reason = lastOutput?.replace(LOG_PREFIX, "")
        fail("OpenFlux exited (code $code)" + if (reason != null) ": $reason" else "")
    }

    private fun pumpOutput(p: Process) {
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    lastOutput = line
                    android.util.Log.d("NativeStdout", line)
                    EventBus.dispatch(AppEvent.LogMessage(line))
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun fail(message: String) {
        Logx.e(TAG, message)
        error = message
        ready.set(false)
        running.set(false)
        deleteKey()
        if (!shuttingDown.get()) handler.post { onUnexpectedExit(message) }
    }

    private fun destroy(p: Process) {
        if (!p.isAlive) return
        p.destroy()
        handler.postDelayed({ if (p.isAlive) p.destroyForcibly() }, STOP_GRACE_MS)
    }

    private fun writeKey(key: String): String {
        val file = keyFile
        file.writeText(EncryptionKey.normalize(key))
        file.setReadable(false, false)
        file.setReadable(true, true)
        return file.absolutePath
    }

    private fun deleteKey() {
        runCatching { keyFile.delete() }
    }
}
