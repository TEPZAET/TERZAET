package io.github.p1neapplexpress.openflux.service

import android.content.Context
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.Loopback
import io.github.p1neapplexpress.openflux.util.ProcessRunner
import java.io.File

class Tun2SocksLauncher(private val context: Context) {

    companion object {
        private const val TAG = "Tun2SocksLauncher"
        private const val SEND_FD_ATTEMPTS = 10
        private const val SEND_FD_BASE_DELAY_MS = 500L

        private const val NETIF_IPADDR = "26.26.26.2"
        private const val NETIF_NETMASK = "255.255.255.0"
        private const val NETIF_IP6ADDR = "fdfe:dcba:9876::2"
        private const val TUN_MTU = 1500
        private const val LOG_LEVEL = "3"

        // VPN interface address (VpnServiceController). tun2socks re-injects DNS
        // queries towards it, so pdnsd must listen there rather than on loopback.
        private const val DNS_GW_IP = "26.26.26.1"
    }

    private var dnsRelay: DnsTcpRelay? = null

    fun start(
        fd: Int,
        socksPort: Int,
        username: String?,
        password: String?,
        ipv6: Boolean,
        udpgw: String?,
    ): Boolean {
        if (fd <= 0) {
            Logx.e(TAG, "invalid tun fd: $fd")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val pdnsdBin = "$nativeDir/libpdnsd.so"
        val tun2socksBin = "$nativeDir/libtun2socks.so"

        val sockPath = File(context.applicationInfo.dataDir, "sock_path").apply {
            if (!exists()) createNewFile()
            setWritable(true, false)
            setReadable(true, false)
        }

        val relay = DnsTcpRelay(socksPort).start()
        dnsRelay = relay
        val dnsPort = Loopback.freeTcpPort()

        makePdnsdConf(listenPort = dnsPort, upstreamPort = relay.port)
        Logx.i(TAG, "starting pdnsd (DNS via tunnel)")
        ProcessRunner.execFireAndForget(
            command = listOf(pdnsdBin, "-c", "${context.filesDir}/pdnsd.conf"),
            workingDir = context.filesDir.absolutePath,
        )
        Thread.sleep(500L)

        Logx.i(TAG, "starting tun2socks")
        ProcessRunner.execFireAndForget(
            command = buildCommand(tun2socksBin, fd, socksPort, dnsPort, username, password, ipv6, udpgw, sockPath),
            workingDir = context.filesDir.absolutePath,
        )
        Thread.sleep(500L)

        for (attempt in 1..SEND_FD_ATTEMPTS) {
            val r = NativeBridge.sendfd(fd, sockPath.absolutePath)
            if (r == 0) {
                Logx.i(TAG, "sendfd ok on attempt $attempt")
                return true
            }
            Logx.w(TAG, "sendfd attempt $attempt failed (ret=$r)")
            try {
                Thread.sleep(SEND_FD_BASE_DELAY_MS * attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        Logx.e(TAG, "sendfd failed after $SEND_FD_ATTEMPTS attempts")
        return false
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        ProcessRunner.killPidFile("${context.filesDir}/tun2socks.pid")
        ProcessRunner.killPidFile("${context.filesDir}/pdnsd.pid")
        dnsRelay?.close()
        dnsRelay = null
        runCatching { File(context.applicationInfo.dataDir, "sock_path").delete() }
    }

    private fun buildCommand(
        bin: String,
        fd: Int,
        socksPort: Int,
        dnsPort: Int,
        user: String?,
        passwd: String?,
        ipv6: Boolean,
        udpgw: String?,
        sockPath: File,
    ): List<String> = buildList {
        add(bin)
        add("--netif-ipaddr"); add(NETIF_IPADDR)
        add("--netif-netmask"); add(NETIF_NETMASK)
        add("--socks-server-addr"); add("127.0.0.1:$socksPort")
        add("--tunfd"); add(fd.toString())
        add("--tunmtu"); add(TUN_MTU.toString())
        add("--loglevel"); add(LOG_LEVEL)
        add("--pid"); add("${context.filesDir}/tun2socks.pid")
        add("--sock"); add(sockPath.absolutePath)
        if (!user.isNullOrEmpty()) {
            add("--username"); add(user)
            add("--password"); add(passwd ?: "")
        }
        if (ipv6) { add("--netif-ip6addr"); add(NETIF_IP6ADDR) }
        add("--dnsgw"); add("$DNS_GW_IP:$dnsPort")
        udpgw?.let { add("--udpgw-remote-server-addr"); add(it) }
    }

    private fun makePdnsdConf(listenPort: Int, upstreamPort: Int) {
        val conf = context.getString(io.github.p1neapplexpress.openflux.R.string.pdnsd_conf)
            .replace("{DIR}", context.filesDir.toString())
            .replace("{LISTEN_IP}", DNS_GW_IP)
            .replace("{LISTEN_PORT}", listenPort.toString())
            .replace("{UPSTREAM_PORT}", upstreamPort.toString())

        val f = File(context.filesDir, "pdnsd.conf")
        f.writeText(conf)

        val cache = File(context.filesDir, "pdnsd.cache")
        if (!cache.exists()) cache.createNewFile()
    }
}
