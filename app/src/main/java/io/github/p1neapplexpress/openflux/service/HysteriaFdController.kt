package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.NativeBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HysteriaFdController(
    private val protect: (Int) -> Boolean,
    private val onFailure: (String) -> Unit,
    private val onProtected: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var serverFd = -1

    fun start(path: File): Boolean = runCatching {
        serverFd = NativeBridge.createfdcontrol(path.absolutePath)
        check(serverFd >= 0)
        running.set(true)
        thread(name = "HysteriaFdControl", isDaemon = true) {
            while (running.get()) {
                val packet = NativeBridge.acceptfdcontrol(serverFd)
                if (packet < 0) break
                val controlFd = (packet ushr 32).toInt()
                val descriptor = packet.toInt()
                val ok = descriptor >= 0 && protect(descriptor)
                if (descriptor >= 0) NativeBridge.jniclose(descriptor)
                if (!ok) onFailure("Не удалось защитить UDP-сокет Hysteria 2")
                else onProtected()
                NativeBridge.finishfdcontrol(controlFd, ok)
            }
        }
        true
    }.getOrDefault(false)

    fun stop() {
        running.set(false)
        if (serverFd >= 0) NativeBridge.jniclose(serverFd)
        serverFd = -1
    }

}
