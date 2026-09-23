package io.github.p1neapplexpress.openflux.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.ConnectionMode
import io.github.p1neapplexpress.openflux.data.TunnelRepository
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.data.TunnelViewType
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import io.github.p1neapplexpress.openflux.vpn.VpnIntentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TunnelsViewModel"
        private const val POLL_MS = 250L
        private const val BIND_TIMEOUT_MS = 5_000L

        private const val TRANSPORT_TIMEOUT_MS = 50_000L
        private const val TUN2SOCKS_TIMEOUT_MS = 10_000L
        private const val SHUTDOWN_TIMEOUT_MS = 6_000L
    }

    private val repo = TunnelRepository(app)

    @Volatile
    private var service: IUnifiedService? = null
    private var bound = false
    private var activeTunnelData: Tunnel? = null

    private var startJob: Job? = null
    private var teardownJob: Job? = null

    @Volatile
    private var bindRequested = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUnifiedService.Stub.asInterface(binder)
            bound = true
            Logx.d(TAG, "service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Logx.d(TAG, "service disconnected")
            if (_active.value is TunnelState.Stopping || _active.value is TunnelState.Unavailable || teardownJob?.isActive == true) return
            if (_active.value !is TunnelState.Idle) {
                _active.value = TunnelState.Idle
                stopUptimeCounter()
                refresh()
            }
        }
    }

    private val _tunnels = MutableStateFlow<List<TunnelViewType>>(emptyList())
    val tunnels: StateFlow<List<TunnelViewType>> = _tunnels.asStateFlow()

    private val _active = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val active: StateFlow<TunnelState> = _active.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _pingMs = MutableStateFlow<Long?>(null)
    val pingMs: StateFlow<Long?> = _pingMs.asStateFlow()

    private val _selected = MutableStateFlow<Tunnel?>(null)
    val selected: StateFlow<Tunnel?> = _selected.asStateFlow()

    private val _activeTransport = MutableStateFlow(AppEvent.Transport.YANDEX)
    val activeTransport: StateFlow<AppEvent.Transport> = _activeTransport.asStateFlow()

    val selectedTunnelId: Long? get() = _selected.value?.id

    private var uptimeJob: Job? = null
    private var pingJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            EventBus.events.collect { ev ->
                if (ev is AppEvent.TransportChanged) {
                    _activeTransport.value = ev.transport
                }
                if (ev is AppEvent.ConnectionStatus) {
                    val tunnel = activeTunnelData ?: _active.value.tunnel
                    if (tunnel != null) {
                        when (ev.status) {
                            AppEvent.Status.CHECKING -> if (_active.value is TunnelState.Restoring) _active.value = TunnelState.Restoring(tunnel, 1)
                            AppEvent.Status.WAITING_FOR_NETWORK -> _active.value = TunnelState.Restoring(tunnel, 0)
                            AppEvent.Status.RESTORING -> _active.value = TunnelState.Restoring(tunnel, 1)
                            AppEvent.Status.RESTORED -> _active.value = TunnelState.Running(tunnel)
                            AppEvent.Status.UNAVAILABLE -> _active.value = TunnelState.Unavailable(tunnel)
                        }
                    }
                }
                if (ev is AppEvent.NativeProcessExited && _active.value.isActive && _active.value !is TunnelState.Unavailable) {
                    fail("TR-203", "Транспорт неожиданно остановлен: ${ev.message}")
                }
                if (ev is AppEvent.VpnRevoked && _active.value !is TunnelState.Idle) {
                    startJob?.cancel()
                    startJob = null
                    if (bindRequested) runCatching { getApplication<Application>().unbindService(connection) }
                    bindRequested = false
                    bound = false
                    service = null
                    activeTunnelData = null
                    _active.value = TunnelState.Idle
                    stopUptimeCounter()
                    refresh()
                    Logx.i(TAG, "Системный VPN переключён на другое приложение")
                }
            }
        }
    }

    fun startCurrent() {
        val tunnel = _active.value.tunnel
            ?: _selected.value
            ?: repo.getSelected()
            ?: return
        startTunnel(tunnel)
    }

    fun startTunnel(tunnel: Tunnel) {
        val running = _active.value
        val alreadyStarting = running is TunnelState.Connecting ||
            running is TunnelState.StartingTransport ||
            running is TunnelState.StartingTun2Socks ||
            running is TunnelState.Checking ||
            running is TunnelState.Restoring ||
            running is TunnelState.Running
        if (alreadyStarting && running.tunnel?.id == tunnel.id) return
        if (alreadyStarting) stop()
        val pendingTeardown = teardownJob

        repo.setSelectedId(tunnel.id)
        _selected.value = tunnel
        _active.value = TunnelState.Connecting(tunnel)

        startJob = viewModelScope.launch(Dispatchers.IO) {
            pendingTeardown?.join()
            _active.value = TunnelState.Connecting(tunnel)

            val ctx = getApplication<Application>()
            val serviceStarted = runCatching {
                val intent = VpnIntentFactory.build(ctx, VPNConfig(name = tunnel.name))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
                ctx.bindService(
                    Intent(ctx, SocksVpnService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrElse { error ->
                runCatching { ctx.stopService(Intent(ctx, SocksVpnService::class.java)) }
                fail("VPN-100", "Не удалось запустить VPN: ${error.message ?: error.javaClass.simpleName}")
                return@launch
            }
            if (!serviceStarted) {
                runCatching { ctx.stopService(Intent(ctx, SocksVpnService::class.java)) }
                fail("VPN-100", "Системная служба VPN не запустилась")
                return@launch
            }
            bindRequested = true
            activeTunnelData = tunnel

            if (awaitBinding() == null) {
                fail("VPN-101", "Системная служба VPN не ответила")
                return@launch
            }
            if (runCatching { service?.getFd() ?: -1 }.getOrDefault(-1) <= 0) {
                fail("VPN-102", "Не удалось создать VPN-интерфейс")
                return@launch
            }
            Logx.i(TAG, "service bound, starting transport")

            _active.value = TunnelState.StartingTransport(tunnel)
            try {
                val mode = ConnectionMode.from(tunnel.connectionMode)
                val hysteriaUri = tunnel.hysteriaUri
                val usesHysteria = mode != ConnectionMode.yandex && !hysteriaUri.isNullOrBlank()
                _activeTransport.value = if (usesHysteria) AppEvent.Transport.HYSTERIA2 else AppEvent.Transport.YANDEX
                if (usesHysteria) {
                    service?.startHysteriaNative(
                        hysteriaUri,
                        tunnel.transportConnPayload.toTypedArray(),
                        tunnel.encryptionKey,
                        mode == ConnectionMode.auto && tunnel.autoFallback,
                    )
                } else {
                    service?.startOpenFluxNative(
                        tunnel.transportType,
                        tunnel.transportConnPayload.toTypedArray(),
                        tunnel.encryptionKey,
                    )
                }
            } catch (e: Exception) {
                fail("TR-201", "Не удалось запустить транспорт: ${e.message}")
                return@launch
            }

            awaitService(TRANSPORT_TIMEOUT_MS, "TR-202", "Транспорт не запустился за отведённое время") { it.isFServiceRunning() }
                ?.let { fail(it); return@launch }

            _active.value = TunnelState.Checking(tunnel)
            var verifiedLatency = waitForDataPath(35_000L)

            val mode = ConnectionMode.from(tunnel.connectionMode)
            if (verifiedLatency < 0L && mode == ConnectionMode.auto && tunnel.autoFallback && !tunnel.hysteriaUri.isNullOrBlank()) {
                val documentName = if (tunnel.transportType == "mailru") "Mail Документы" else "Яндекс Документы"
                EventBus.dispatch(AppEvent.LogMessage("Hysteria 2 не пропускает трафик · переключаемся на $documentName"))
                runCatching { service?.stopOpenFluxNative() }
                _activeTransport.value = AppEvent.Transport.YANDEX
                runCatching {
                    service?.startOpenFluxNative(
                        tunnel.transportType,
                        tunnel.transportConnPayload.toTypedArray(),
                        tunnel.encryptionKey,
                    )
                }.onFailure {
                    fail("TR-204", "Не удалось включить резервный транспорт")
                    return@launch
                }
                awaitService(TRANSPORT_TIMEOUT_MS, "TR-205", "Резервный транспорт не запустился") { it.isFServiceRunning() }
                    ?.let { fail(it); return@launch }
                verifiedLatency = waitForDataPath(20_000L)
            }

            if (verifiedLatency >= 0L) {
                Logx.i(TAG, "data path verified in ${verifiedLatency}ms, starting tun2socks")
            } else {
                Logx.w(TAG, "server data path is unavailable")
                EventBus.dispatch(AppEvent.LogMessage("Сервер не отвечает"))
                teardown(TunnelState.Unavailable(tunnel))
                return@launch
            }
            _active.value = TunnelState.StartingTun2Socks(tunnel)
            try {
                service?.startTun2Socks()
            } catch (e: Exception) {
                fail("TUN-301", "Не удалось запустить tun2socks: ${e.message}")
                return@launch
            }

            awaitService(TUN2SOCKS_TIMEOUT_MS, "TUN-302", "VPN-канал не перешёл в рабочее состояние") { it.isVpnRunning() }
                ?.let { fail(it); return@launch }

            _pingMs.value = null
            _active.value = TunnelState.Running(tunnel)
            startUptimeCounter()
            startPingCounter()
            refresh()
        }
    }

    private suspend fun waitForDataPath(timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service == null || runCatching { service?.nativeError() != null }.getOrDefault(true)) return -1L
            val latency = runCatching { service?.measureDataPathLatency() ?: -1L }.getOrDefault(-1L)
            if (latency >= 0L) return latency
            delay(750L)
        }
        return -1L
    }

    private suspend fun awaitService(
        timeoutMs: Long,
        timeoutCode: String,
        timeoutMessage: String,
        ready: (IUnifiedService) -> Boolean,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = service ?: return errorText("VPN-101", "Системная служба VPN отключилась")
            try {
                s.nativeError()?.let { return errorText("TR-203", it) }
                if (ready(s)) return null
            } catch (e: Exception) {
                Logx.e(TAG, "service call failed", e)
            }
            delay(POLL_MS)
        }
        return errorText(timeoutCode, timeoutMessage)
    }

    fun stop() {
        if (_active.value is TunnelState.Stopping) return
        Logx.i(TAG, "stop()")
        teardown(TunnelState.Idle)
    }

    private fun fail(code: String, message: String) = fail(errorText(code, message))

    private fun fail(message: String) {
        if (startJob == null && teardownJob?.isActive == true) return
        Logx.e(TAG, message)
        teardown(TunnelState.Error(message))
    }

    private fun teardown(finalState: TunnelState) {
        if (teardownJob?.isActive == true) return
        val closingTunnel = _active.value.tunnel ?: activeTunnelData
        _active.value = TunnelState.Stopping(closingTunnel)
        startJob?.cancel()
        startJob = null
        val previous = teardownJob
        teardownJob = CoroutineScope(Dispatchers.IO).launch {
            previous?.join()
            if (bindRequested) {
                val s = awaitBinding()
                var stoppedCleanly = true
                try {
                    s?.stopOpenFluxNative()
                    s?.stopVpn()
                    val deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS
                    while (s != null && !s.isShutdownComplete() && System.currentTimeMillis() < deadline) {
                        delay(POLL_MS)
                    }
                    stoppedCleanly = s?.isShutdownComplete() != false
                } catch (e: Exception) {
                    Logx.e(TAG, "stop failed", e)
                    stoppedCleanly = false
                }
                try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
                bindRequested = false
                val app = getApplication<Application>()
                app.stopService(Intent(app, SocksVpnService::class.java))
                delay(300L)
                if (!stoppedCleanly && finalState is TunnelState.Idle) {
                    _active.value = TunnelState.Error(errorText("OFF-501", "VPN отключён не полностью; повторите отключение"))
                }
            }
            bound = false
            service = null
            activeTunnelData = null
            if (_active.value is TunnelState.Stopping) _active.value = finalState
            stopUptimeCounter()
            refresh()
        }
    }

    private fun errorText(code: String, message: String) = "[$code] $message"

    private suspend fun awaitBinding(): IUnifiedService? {
        var waited = 0L
        while (service == null && waited < BIND_TIMEOUT_MS) {
            delay(50)
            waited += 50
        }
        return service
    }

    fun refresh() {
        val list = repo.load()
        val running = (_active.value as? TunnelState.Running)?.tunnel
        _tunnels.value = list.map { TunnelViewType(it, enabled = it == running) }
        _selected.value = repo.getSelected()
    }

    fun reconcileSystemState() {
        val state = _active.value
        if (state !is TunnelState.Running && state !is TunnelState.Restoring) return
        val app = getApplication<Application>()
        val runtimeActive = app
            .getSharedPreferences("vpn_runtime", Context.MODE_PRIVATE)
            .getBoolean("active", false)
        val vpnOwnershipLost = VpnService.prepare(app) != null
        if (!runtimeActive || vpnOwnershipLost) {
            app.stopService(Intent(app, SocksVpnService::class.java))
            startJob?.cancel()
            startJob = null
            activeTunnelData = null
            _active.value = TunnelState.Idle
            stopUptimeCounter()
            refresh()
        }
    }

    fun selectTunnel(tunnel: Tunnel) {
        if (_active.value.isActive) {
            stop()
        }
        repo.setSelectedId(tunnel.id)
        _selected.value = repo.getSelected()
    }

    fun addTunnel(tunnel: Tunnel) {
        val current = repo.load().toMutableList()
        if (current.none { it.id == tunnel.id }) {
            current.add(tunnel)
            repo.save(current)
            refresh()
        }
    }

    fun removeTunnel(tunnel: Tunnel) {
        if (!tunnel.adminHost.isNullOrBlank()) return
        if (_active.value.isActive && _active.value.tunnel?.id == tunnel.id) return
        val current = repo.load().toMutableList()
        current.removeAll { it.id == tunnel.id }
        repo.save(current)
        refresh()
    }

    fun updateTunnel(old: Tunnel, new: Tunnel) {
        if (_active.value.isActive && _active.value.tunnel?.id == old.id) return
        val current = repo.load().toMutableList()
        val idx = current.indexOfFirst { it.id == old.id }
        if (idx < 0) return
        current[idx] = new
        repo.save(current)
        refresh()
    }

    fun setConnectionMode(tunnel: Tunnel, mode: ConnectionMode) {
        if (_active.value.isActive && _active.value !is TunnelState.Unavailable) return
        val current = repo.load().toMutableList()
        val index = current.indexOfFirst { it.id == tunnel.id }
        if (index < 0) return
        current[index] = current[index].copy(connectionMode = mode.name)
        repo.save(current)
        refresh()
    }

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        _uptimeSeconds.value = 0L
        uptimeJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                _uptimeSeconds.value = (System.currentTimeMillis() - startedAt) / 1000L
                delay(1_000L)
            }
        }
    }

    private fun stopUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = null
        pingJob?.cancel()
        pingJob = null
        _uptimeSeconds.value = 0L
        _pingMs.value = null
    }

    private fun startPingCounter() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            delay(8_000L)
            while (isActive) {
                _pingMs.value = runCatching { service?.measureDataPathLatency()?.takeIf { it in 1..5_000 } }.getOrNull()
                delay(60_000L)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        startJob?.cancel()
        stopUptimeCounter()
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}
