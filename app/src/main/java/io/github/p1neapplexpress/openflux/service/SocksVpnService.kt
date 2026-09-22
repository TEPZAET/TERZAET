package io.github.p1neapplexpress.openflux.service

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.Logx
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private lateinit var hysteria: HysteriaSupervisor
    private lateinit var tun2socks: Tun2SocksLauncher
    private lateinit var notifications: VpnNotificationManager

    private var lastIntent: Intent? = null
    private val shutdownComplete = AtomicBoolean(true)
    private val stopping = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null
    private var lastTransportArgs: List<String>? = null
    private var lastEncryptionKey: String? = null
    private var lastHysteriaUri: String? = null
    private var allowHysteriaFallback = false
    private var activeBackend = Backend.YANDEX
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRecoveryJob: Job? = null
    private var networkAvailable = false

    private val binder = object : IUnifiedService.Stub() {
        override fun isVpnRunning(): Boolean = vpn.isRunning.get()
        override fun isShutdownComplete(): Boolean = shutdownComplete.get()
        override fun measureDataPathLatency(): Long = activeLatency()
        override fun stopVpn() = stopEverything()
        override fun isFServiceRunning(): Boolean = activeReady()
        override fun nativeError(): String? = activeError()
        override fun stopOpenFluxNative() = stopClients()

        override fun startOpenFluxNative(transport: String?, args: Array<String>, encryptionKey: String?) {
            transport ?: return
            shutdownComplete.set(false)
            stopping.set(false)
            lastTransportArgs = args.toList()
            lastEncryptionKey = encryptionKey
            lastHysteriaUri = null
            allowHysteriaFallback = false
            activeBackend = Backend.YANDEX
            EventBus.dispatch(AppEvent.TransportChanged(AppEvent.Transport.YANDEX))
            sessionGeneration.incrementAndGet()
            hysteria.stop()
            supervisor.start(args.toList(), encryptionKey)
        }

        override fun startHysteriaNative(uri: String, fallbackArgs: Array<String>, fallbackEncryptionKey: String?, allowFallback: Boolean) {
            shutdownComplete.set(false)
            stopping.set(false)
            lastHysteriaUri = uri
            lastTransportArgs = fallbackArgs.toList()
            lastEncryptionKey = fallbackEncryptionKey
            allowHysteriaFallback = allowFallback && fallbackArgs.isNotEmpty()
            activeBackend = Backend.HYSTERIA
            EventBus.dispatch(AppEvent.TransportChanged(AppEvent.Transport.HYSTERIA2))
            sessionGeneration.incrementAndGet()
            supervisor.stop()
            hysteria.start(uri)
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
                    socksPort = activeSocksPort(),
                    username = i.getStringExtra(Constants.INTENT_USERNAME),
                    password = i.getStringExtra(Constants.INTENT_PASSWORD),
                    ipv6 = i.getBooleanExtra(Constants.INTENT_IPV6_PROXY, false),
                    udpgw = i.getStringExtra(Constants.INTENT_UDP_GW),
                )

                if (ok) {
                    vpn.isRunning.set(true)
                    getSharedPreferences("vpn_runtime", MODE_PRIVATE).edit().putBoolean("active", true).apply()
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
        hysteria = HysteriaSupervisor(applicationContext, { fd -> protect(fd) }) { message ->
            if (allowHysteriaFallback && !stopping.get() && lastTransportArgs?.isNotEmpty() == true) {
                EventBus.dispatch(AppEvent.LogMessage("Hysteria 2 недоступна · переключаемся на Яндекс"))
                activeBackend = Backend.YANDEX
                EventBus.dispatch(AppEvent.TransportChanged(AppEvent.Transport.YANDEX))
                hysteria.stop()
                supervisor.start(lastTransportArgs.orEmpty(), lastEncryptionKey)
            } else if (!reconnecting.get()) {
                stopEverything()
                EventBus.dispatch(AppEvent.NativeProcessExited(message))
            }
        }
        tun2socks = Tun2SocksLauncher(applicationContext)
        notifications = VpnNotificationManager(this)
        registerNetworkObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopEverything()
            return START_NOT_STICKY
        }
        lastIntent = intent
        shutdownComplete.set(false)
        stopping.set(false)
        notifications.startForeground()

        if (vpn.isConfigured()) {
            Logx.d(TAG, "VPN already configured, ignoring")
            return START_NOT_STICKY
        }

        vpn.configure(intent)
        EventBus.dispatch(AppEvent.LogMessage("[S] VPN configured"))
        Logx.i(TAG, "VPN configured")
        return START_NOT_STICKY
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
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startHealthMonitor() {
        healthJob?.cancel()
        val generation = sessionGeneration.get()
        healthJob = serviceScope.launch {
            var failures = 0
            var networkWasMissing = false
            while (isActive && !stopping.get() && generation == sessionGeneration.get()) {
                delay(20_000L)
                if (!hasInternetNetwork()) {
                    networkWasMissing = true
                    failures = 0
                    notifications.updateContent("Нет сети · TERZAET ждёт подключения")
                    EventBus.dispatch(AppEvent.ConnectionStatus(AppEvent.Status.WAITING_FOR_NETWORK))
                    continue
                }
                if (networkWasMissing) {
                    networkWasMissing = false
                    recoverTransport("сеть снова доступна")
                    continue
                }
                val latency = activeLatency(4_000)
                EventBus.dispatch(AppEvent.ConnectionStatus(AppEvent.Status.CHECKING))
                if (latency >= 0L) {
                    failures = 0
                } else {
                    failures++
                    if (failures >= 3) {
                        failures = 0
                        recoverTransport("сервер перестал отвечать", generation)
                    }
                }
            }
        }
    }

    private suspend fun recoverTransport(reason: String, expectedGeneration: Long = sessionGeneration.get()) {
        if (expectedGeneration != sessionGeneration.get()) return
        if (!reconnecting.compareAndSet(false, true) || stopping.get()) return
        val powerManager = getSystemService(PowerManager::class.java)
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:recovery")
        runCatching { wakeLock?.acquire(90_000L) }
        try {
            if (lastTransportArgs == null && lastHysteriaUri == null) return
            notifications.stopSpeedUpdates()
            notifications.updateContent("Связь потеряна · восстанавливаем…")
            EventBus.dispatch(AppEvent.ConnectionStatus(AppEvent.Status.RESTORING))
            EventBus.dispatch(AppEvent.LogMessage("Соединение восстанавливается"))
            var restored = false
            for (attempt in 1..4) {
                if (stopping.get() || expectedGeneration != sessionGeneration.get()) break
                runCatching { tun2socks.stop() }
                vpn.isRunning.set(false)
                stopClients()
                delay((1_000L shl (attempt - 1)).coerceAtMost(8_000L))
                if (activeBackend == Backend.HYSTERIA && allowHysteriaFallback) {
                    EventBus.dispatch(AppEvent.LogMessage("Hysteria 2 недоступна · переключаемся на Яндекс"))
                    activeBackend = Backend.YANDEX
                    EventBus.dispatch(AppEvent.TransportChanged(AppEvent.Transport.YANDEX))
                    supervisor.start(lastTransportArgs.orEmpty(), lastEncryptionKey)
                } else if (activeBackend == Backend.HYSTERIA && !lastHysteriaUri.isNullOrBlank()) {
                    hysteria.start(lastHysteriaUri!!)
                } else {
                    activeBackend = Backend.YANDEX
                    EventBus.dispatch(AppEvent.TransportChanged(AppEvent.Transport.YANDEX))
                    supervisor.start(lastTransportArgs.orEmpty(), lastEncryptionKey)
                }
                val deadline = System.currentTimeMillis() + 45_000L
                while (!activeReady() && activeError() == null && System.currentTimeMillis() < deadline && !stopping.get() && expectedGeneration == sessionGeneration.get()) {
                    delay(250L)
                }
                if (activeReady() && activeLatency(5_000) >= 0L) {
                    binder.startTun2Socks()
                    restored = vpn.isRunning.get()
                    if (restored) break
                }
            }
            if (restored) {
                notifications.stopSpeedUpdates()
                notifications.updateContent("Подключение восстановлено")
                notifications.showRecoverySuccess()
                EventBus.dispatch(AppEvent.ConnectionStatus(AppEvent.Status.RESTORED))
                EventBus.dispatch(AppEvent.LogMessage("Подключение восстановлено"))
                delay(4_000L)
                if (!stopping.get() && expectedGeneration == sessionGeneration.get()) {
                    notifications.startSpeedUpdates()
                    startHealthMonitor()
                }
            } else if (!stopping.get() && expectedGeneration == sessionGeneration.get()) {
                notifications.updateContent("Сервер недоступен · откройте TERZAET")
                EventBus.dispatch(AppEvent.ConnectionStatus(AppEvent.Status.UNAVAILABLE))
                EventBus.dispatch(AppEvent.NativeProcessExited("Не удалось восстановить соединение автоматически"))
                stopEverything()
            }
        } finally {
            reconnecting.set(false)
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    private fun registerNetworkObserver() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasUnavailable = !networkAvailable
                networkAvailable = true
                if (wasUnavailable && vpn.isRunning.get() && !reconnecting.get() && !stopping.get()) {
                    networkRecoveryJob?.cancel()
                    val generation = sessionGeneration.get()
                    networkRecoveryJob = serviceScope.launch {
                        delay(2_000L)
                        if (hasInternetNetwork()) recoverTransport("network changed", generation)
                    }
                }
            }

            override fun onLost(network: Network) {
                networkAvailable = hasInternetNetwork()
                if (!networkAvailable && vpn.isRunning.get()) {
                    notifications.updateContent("Нет сети · подключение будет восстановлено")
                    EventBus.dispatch(AppEvent.ConnectionStatus(AppEvent.Status.WAITING_FOR_NETWORK))
                }
            }
        }
        networkCallback = callback
        networkAvailable = hasInternetNetwork()
        manager.registerDefaultNetworkCallback(callback)
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
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        sessionGeneration.incrementAndGet()
        reconnecting.set(false)
        notifications.stopSpeedUpdates()
        runCatching { tun2socks.stop() }
        stopClients()
        runCatching { vpn.stop() }
        runCatching { StaleProcesses.kill(applicationInfo.nativeLibraryDir) }
        lastIntent = null
        shutdownComplete.set(true)
        getSharedPreferences("vpn_runtime", MODE_PRIVATE).edit().putBoolean("active", false).apply()
        notifications.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private enum class Backend { YANDEX, HYSTERIA }

    private fun activeReady() = if (activeBackend == Backend.HYSTERIA) hysteria.isReady else supervisor.isReady

    private fun activeError() = if (activeBackend == Backend.HYSTERIA) hysteria.error else supervisor.error

    private fun activeLatency(timeoutMs: Int = 5_000) = if (activeBackend == Backend.HYSTERIA) {
        hysteria.measureDataPathLatency(timeoutMs)
    } else {
        supervisor.measureDataPathLatency(timeoutMs)
    }

    private fun activeSocksPort() = if (activeBackend == Backend.HYSTERIA) hysteria.socksPort else supervisor.socksPort

    private fun stopClients() {
        runCatching { supervisor.stop() }
        runCatching { hysteria.stop() }
    }
}
