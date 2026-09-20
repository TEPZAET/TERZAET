package io.github.p1neapplexpress.openflux.util

object Constants {
    const val ROUTE_ALL = "all"
    const val ROUTE_CHN = "chn"

    private const val INTENT_PREFIX = "SOCKS"
    const val INTENT_NAME = INTENT_PREFIX + "NAME"
    const val INTENT_SERVER = INTENT_PREFIX + "SERV"
    const val INTENT_PORT = INTENT_PREFIX + "PORT"
    const val INTENT_USERNAME = INTENT_PREFIX + "UNAME"
    const val INTENT_PASSWORD = INTENT_PREFIX + "PASSWD"
    const val INTENT_ROUTE = INTENT_PREFIX + "ROUTE"
    const val INTENT_DNS = INTENT_PREFIX + "DNS"
    const val INTENT_DNS_PORT = INTENT_PREFIX + "DNSPORT"
    const val INTENT_PER_APP = INTENT_PREFIX + "PERAPP"
    const val INTENT_APP_BYPASS = INTENT_PREFIX + "APPBYPASS"
    const val INTENT_APP_LIST = INTENT_PREFIX + "APPLIST"
    const val INTENT_IPV6_PROXY = INTENT_PREFIX + "IPV6"
    const val INTENT_UDP_GW = INTENT_PREFIX + "UDPGW"

    const val PREF = "profile"
    const val PREF_PROFILE = "profile"
    const val PREF_LAST_PROFILE = "last_profile"
    const val PREF_TUNNELS_KEY = "tunnels"
    const val PREF_SELECTED_TUNNEL_ID = "selected_tunnel_id"
}
