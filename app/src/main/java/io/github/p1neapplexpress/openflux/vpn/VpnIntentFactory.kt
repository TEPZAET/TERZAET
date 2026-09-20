package io.github.p1neapplexpress.openflux.vpn

import android.content.Context
import android.content.Intent
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.Constants

object VpnIntentFactory {
    fun build(context: Context, cfg: VPNConfig): Intent =
        Intent(context, SocksVpnService::class.java).apply {
            putExtra(Constants.INTENT_NAME, cfg.name)
            putExtra(Constants.INTENT_SERVER, cfg.server)
            putExtra(Constants.INTENT_PORT, cfg.port)
            putExtra(Constants.INTENT_ROUTE, cfg.route)
            putExtra(Constants.INTENT_DNS, cfg.dns)
            putExtra(Constants.INTENT_DNS_PORT, cfg.dnsPort)
            putExtra(Constants.INTENT_PER_APP, cfg.perApp)
            putExtra(Constants.INTENT_APP_BYPASS, cfg.appBypass)
            putExtra(Constants.INTENT_APP_LIST, cfg.appList)
            putExtra(Constants.INTENT_IPV6_PROXY, cfg.ipv6Proxy)
            cfg.udpGw?.let { putExtra(Constants.INTENT_UDP_GW, it) }
            cfg.username?.let { putExtra(Constants.INTENT_USERNAME, it) }
            cfg.password?.let { putExtra(Constants.INTENT_PASSWORD, it) }
        }
}
