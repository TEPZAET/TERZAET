package io.github.p1neapplexpress.openflux.vpn

import io.github.p1neapplexpress.openflux.util.Constants

data class VPNConfig(
    val name: String,
    val server: String = "127.0.0.1",
    val port: Int = 1080,
    val username: String? = null,
    val password: String? = null,
    val route: String = Constants.ROUTE_ALL,
    val dns: String = "8.8.8.8",
    val dnsPort: Int = 53,
    val perApp: Boolean = false,
    val appBypass: Boolean = false,
    val appList: Array<String> = emptyArray(),
    val ipv6Proxy: Boolean = false,
    val udpGw: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VPNConfig) return false
        return name == other.name &&
            server == other.server &&
            port == other.port &&
            username == other.username &&
            password == other.password &&
            route == other.route &&
            dns == other.dns &&
            dnsPort == other.dnsPort &&
            perApp == other.perApp &&
            appBypass == other.appBypass &&
            appList.contentEquals(other.appList) &&
            ipv6Proxy == other.ipv6Proxy &&
            udpGw == other.udpGw
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + server.hashCode()
        result = 31 * result + port
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (password?.hashCode() ?: 0)
        result = 31 * result + route.hashCode()
        result = 31 * result + dns.hashCode()
        result = 31 * result + dnsPort
        result = 31 * result + perApp.hashCode()
        result = 31 * result + appBypass.hashCode()
        result = 31 * result + appList.contentHashCode()
        result = 31 * result + ipv6Proxy.hashCode()
        result = 31 * result + (udpGw?.hashCode() ?: 0)
        return result
    }
}
