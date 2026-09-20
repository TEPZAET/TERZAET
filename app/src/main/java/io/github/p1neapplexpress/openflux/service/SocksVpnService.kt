package io.github.p1neapplexpress.openflux.service

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.Logx
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@SuppressLint("VpnServicePolicy")
class SocksVpnService : android.net.VpnService() {

    companion object {
        private const val TAG = "SocksVpnService"
    }

    private lateinit var vpn: VpnServiceController
    private lateinit var supervisor: NativeProcessSupervisor
    private lateinit var tun2socks: Tun2SocksLauncher
    private lateinit var notifications: VpnNotificationManager

    private var lastIntent: Intent? = null
    private val shutdownComplete = AtomicBoolean(true)
    private val stopping = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null
    private var lastTransportArgs: List<String>? = null
    private var lastEncryptionKey: String? = null

    private val binder = object : IUnifiedService.Stub() {
        override fun isVpnRunning(): Boolean = vpn.isRunning.get()
        override fun isShutdownComplete(): Boolean = shutdownComplete.get()
        override fun measureDataPathLatency(): Long = supervisor.measureDataPathLatency()
        override fun stopVpn() = stopEverything()
        override fun isFServiceRunning(): Boolean = supervisor.isReady
        override fun nativeError(): String? = supervisor.error
        override fun stopOpenFluxNative() = supervisor.stop()

        override fun startOpenFluxNative(transport: String?, args: Array<String>, encryptionKey: String?) {
            transport ?: return
            shutdownComplete.set(false)
            stopping.set(false)
            lastTransportArgs = args.toList()
            lastEncryptionKey = encryptionKey
            supervisor.start(args.toList(), encryptionKey)
        }

        override fun startTun2Socks() {
            synchronized(this@SocksVpnService) {
                val fd = vpn.fd
                if (fd <= 0) {
                    Logx.e(TAG, "no tun fd; aborting tun2socks start")
                    return
                }
                val i = lastIntent ?: run {
                    Logx.e(TAG, "no lastIntent; aborting tun2socks start")
                    return
                }

                val ok = tun2socks.start(
                    fd = fd,
                    socksPort = supervisor.socksPort,
                    username = i.getStringExtra(Constants.INTENT_USERNAME),
                    password = i.getStringExtra(Constants.INTENT_PASSWORD),
                    ipv6 = i.getBooleanExtra(Constants.INTENT_IPV6_PROXY, false),
                    udpgw = i.getStringExtra(Constants.INTENT_UDP_GW),
                )

                if (ok) {
                    vpn.isRunning.set(true)
                    notifications.startSpeedUpdates()
                    EventBus.dispatch(AppEvent.LogMessage("[I] tun2socks running"))
                    Logx.i(TAG, "tun2socks running")
                    if (!reconnecting.get()) startHealthMonitor()
                } else {
                    Logx.e(TAG, "tun2socks failed")
                    EventBus.dispatch(AppEvent.LogMessage("[E] tun2socks failed"))
                    stopEverything()
                }
            }
        }

        override fun getFd(): Int = vpn.fd
    }

    override fun onCreate() {
        super.onCreate()
        NativeBridge.ensureLoaded(applicationContext)
        vpn = VpnServiceController(this)
        supervisor = NativeProcessSupervisor(applicationContext) { message ->
            if (reconnecting.get()) {
                Logx.w(TAG, "transport restart attempt failed: $message")
            } else {
                stopEverything()
                EventBus.dispatch(AppEvent.NativeProcessExited(message))
            }
        }
        tun2socks = Tun2SocksLauncher(applicationContext)
        notifications = VpnNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_STICKY
        lastIntent = intent
        shutdownComplete.set(false)
        stopping.set(false)
        notifications.startForeground()

        if (vpn.isConfigured()) {
            Logx.d(TAG, "VPN already configured, ignoring")
            return START_STICKY
        }

        vpn.configure(intent)
        EventBus.dispatch(AppEvent.LogMessage("[S] VPN configured"))
        Logx.i(TAG, "VPN configured")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onRevoke() {
        Logx.w(TAG, "onRevoke")
        stopEverything()
        EventBus.dispatch(AppEvent.VpnRevoked)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEverything()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            var failures = 0
            var networkWasMissing = false
            while (isActive && !stopping.get()) {
                delay(10_000L)
                if (!hasInternetNetwork()) {
                    networkWasMissing = true
                    failures = 0
                    notifications.updateContent("Нет сети · TERZAET ждёт подключения")
                    continue
                }
                if (networkWasMissing) {
                    networkWasMissing = false
                    recoverTransport("сеть снова доступна")
                    continue
                }
                val latency = supervisor.measureDataPathLatency(3_000)
                if (latency >= 0L) {
                    failures = 0
                } else {
                    failures++
                    if (failures >= 3) {
                        failures = 0
                        recoverTransport("сервер перестал отвечать")
                    }
                }
            }
        }
    }

    private suspend fun recoverTransport(reason: String) {
        if (!reconnecting.compareAndSet(false, true) || stopping.get()) return
        val args = lastTransportArgs
        if (args == null) {
            reconnecting.set(false)
            return
        }
        notifications.stopSpeedUpdates()
        notifications.updateContent("Связь потеряна · восстанавливаем…")
        EventBus.dispatch(AppEvent.LogMessage("[W] Автовосстановление: $reason"))
        var restored = false
        for (attempt in 1..3) {
            if (stopping.get()) break
            runCatching { tun2socks.stop() }
            vpn.isRunning.set(false)
            runCatching { supervisor.stop() }
            delay(600L * attempt)
            supervisor.start(args, lastEncryptionKey)
            val deadline = System.currentTimeMillis() + 45_000L
            while (!supervisor.isReady && supervisor.error == null && System.currentTimeMillis() < deadline && !stopping.get()) {
                delay(250L)
            }
            if (supervisor.isReady && supervisor.measureDataPathLatency(5_000) >= 0L) {
                binder.startTun2Socks()
                restored = vpn.isRunning.get()
                if (restored) break
            }
        }
        reconnecting.set(false)
        if (restored) {
            notifications.stopSpeedUpdates()
            notifications.updateContent("Подключение восстановлено")
            EventBus.dispatch(AppEvent.LogMessage("[I] Подключение восстановлено автоматически"))
            delay(4_000L)
            if (!stopping.get()) {
                notifications.startSpeedUpdates()
                startHealthMonitor()
            }
        } else if (!stopping.get()) {
            EventBus.dispatch(AppEvent.NativeProcessExited("Не удалось восстановить соединение автоматически"))
            stopEverything()
        }
    }

    private fun hasInternetNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Synchronized
    private fun stopEverything() {
        if (!stopping.compareAndSet(false, true)) return
        Logx.i(TAG, "stopEverything")
        healthJob?.cancel()
        healthJob = null
        reconnecting.set(false)
        notifications.stopSpeedUpdates()
        runCatching { tun2socks.stop() }
        runCatching { supervisor.stop() }
        runCatching { vpn.stop() }
        runCatching { StaleProcesses.kill(applicationInfo.nativeLibraryDir) }
        lastIntent = null
        shutdownComplete.set(true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
