package io.github.p1neapplexpress.openflux.util

import java.io.File
import java.util.concurrent.TimeUnit

object ProcessRunner {

    /**
     * Fire-and-forget: starts a process, reads its stdout in a background
     * thread, does not wait for completion. For daemons (pdnsd, tun2socks).
     */
    fun execFireAndForget(
        command: List<String>,
        workingDir: String? = null,
    ): Process? {
        try {
            val pb = ProcessBuilder(command).redirectErrorStream(true)
            if (workingDir != null) pb.directory(File(workingDir))
            val p = pb.start()
            Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) {
                                android.util.Log.d("Exec", line)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
            return p
        } catch (e: Exception) {
            Logx.e("ProcessRunner", "exec failed: ${command.firstOrNull()}", e)
            return null
        }
    }

    fun isPidAlive(path: String): Boolean {
        val pid = runCatching { File(path).readText().trim().toIntOrNull() }.getOrNull() ?: return false
        return runCatching { android.system.Os.kill(pid, 0); true }.getOrDefault(false)
    }

    fun killPidFile(path: String) {
        val f = File(path)
        if (!f.exists()) return
        try {
            val pid = f.readText().trim().toIntOrNull() ?: return
            ProcessBuilder("kill", pid.toString()).start().waitFor(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            f.delete()
        }
    }

    fun join(list: List<String>, sep: String): String = list.joinToString(sep)
}
