package io.github.p1neapplexpress.openflux.service

import android.net.LocalServerSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HysteriaFdController(
    private val protect: (Int) -> Boolean,
) {
    private val running = AtomicBoolean(false)
    private var server: LocalServerSocket? = null

    fun start(path: File): Boolean = runCatching {
        path.delete()
        server = LocalServerSocket(path.absolutePath)
        running.set(true)
        thread(name = "HysteriaFdControl", isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { server?.accept() }.getOrNull() ?: break
                socket.use {
                    val descriptor = it.ancillaryFileDescriptors?.firstOrNull()
                    val ok = descriptor?.let(::protectDescriptor) ?: false
                    runCatching { it.outputStream.write(if (ok) 1 else 0) }
                }
            }
        }
        true
    }.getOrDefault(false)

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun protectDescriptor(descriptor: FileDescriptor): Boolean {
        var duplicate: ParcelFileDescriptor? = null
        return try {
            duplicate = ParcelFileDescriptor.dup(descriptor)
            val raw = duplicate.detachFd()
            try {
                protect(raw)
            } finally {
                ParcelFileDescriptor.adoptFd(raw).close()
            }
        } catch (_: IOException) {
            false
        } finally {
            runCatching { duplicate?.close() }
            runCatching { Os.close(descriptor) }
        }
    }
}
