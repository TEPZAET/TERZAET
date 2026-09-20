package io.github.p1neapplexpress.openflux.service

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.Routes
import java.util.concurrent.atomic.AtomicBoolean

class VpnServiceController(private val service: VpnService) {

    companion object {
        private const val TAG = "VpnServiceController"
        private const val MTU = 1500
        private const val VPN_IPV4_ADDR = "26.26.26.1"
        private const val VPN_IPV4_PREFIX = 24
        private const val VPN_IPV6_ADDR = "fdfe:dcba:9876::1"
        private const val VPN_IPV6_PREFIX = 126
        private const val LOOPBACK_DNS = "8.8.8.8"
    }

    private var iface: ParcelFileDescriptor? = null
    val isRunning = AtomicBoolean(false)

    val fd: Int get() = iface?.fd ?: -1

    fun isConfigured(): Boolean = iface != null

    fun configure(intent: Intent) {
        if (iface != null) {
            Logx.w(TAG, "configure() called twice; ignoring")
            return
        }

        val name = intent.getStringExtra(Constants.INTENT_NAME) ?: "OpenFlux"
        val route = intent.getStringExtra(Constants.INTENT_ROUTE)
        val perApp = intent.getBooleanExtra(Constants.INTENT_PER_APP, false)
        val appBypass = intent.getBooleanExtra(Constants.INTENT_APP_BYPASS, false)
        val appList = intent.getStringArrayExtra(Constants.INTENT_APP_LIST) ?: emptyArray()
        val ipv6 = intent.getBooleanExtra(Constants.INTENT_IPV6_PROXY, false)

        val builder = service.Builder()
            .setMtu(MTU)
            .setSession(name)
            .addAddress(VPN_IPV4_ADDR, VPN_IPV4_PREFIX)
            .addDnsServer(LOOPBACK_DNS)

        if (ipv6) {
            builder.addAddress(VPN_IPV6_ADDR, VPN_IPV6_PREFIX)
                .addRoute("::", 0)
        }

        Routes.addRoutes(service, builder, route)
        builder.addRoute(LOOPBACK_DNS, 32)

        runCatching { builder.addDisallowedApplication(service.packageName) }
            .onFailure { Logx.w(TAG, "disallow self failed: ${it.message}") }

        if (perApp) configureAppRouting(builder, appBypass, appList)

        iface = builder.establish()
        if (iface == null) {
            Logx.e(TAG, "Failed to establish VPN interface")
            EventBus.dispatch(AppEvent.LogMessage("[E] VPN establish failed"))
        } else {
            Logx.d(TAG, "VPN interface established fd=${iface?.fd}")
        }
    }

    private fun configureAppRouting(
        builder: VpnService.Builder,
        bypass: Boolean,
        apps: Array<String>,
    ) {
        val self = service.packageName
        for (raw in apps) {
            val pkg = raw.trim()
            if (pkg.isEmpty() || pkg == self) continue
            runCatching {
                if (bypass) builder.addDisallowedApplication(pkg)
                else builder.addAllowedApplication(pkg)
            }.onFailure {
                if (it !is PackageManager.NameNotFoundException) {
                    Logx.w(TAG, "app routing failed for $pkg: ${it.message}")
                }
            }
        }
    }

    fun stop() {
        runCatching { iface?.close() }
            .onFailure { Logx.e(TAG, "close iface failed", it) }
        iface = null
        isRunning.set(false)
    }
}
