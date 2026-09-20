package io.github.p1neapplexpress.openflux.service

import android.os.Process
import io.github.p1neapplexpress.openflux.util.Logx
import java.io.File

/**
 * Kills native helpers left behind by an earlier session (for example after the
 * app process was killed), which would otherwise keep the tunnel's ports and
 * the TUN fd busy.
 */
object StaleProcesses {

    private const val TAG = "StaleProcesses"
    private val BINARIES = listOf(NativeProcessSupervisor.NATIVE_LIB, "libtun2socks.so", "libpdnsd.so")

    fun kill(nativeLibraryDir: String) {
        val targets = BINARIES.map { "$nativeLibraryDir/$it" }.toSet()
        val self = Process.myPid()
        // /proc only lists this app's own processes (hidepid).
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == self) return@forEach
            val exe = runCatching { File(dir, "cmdline").readText().substringBefore('\u0000') }.getOrNull()
            if (exe in targets) {
                Logx.w(TAG, "killing leftover $exe (pid $pid)")
                Process.killProcess(pid)
            }
        }
    }
}
