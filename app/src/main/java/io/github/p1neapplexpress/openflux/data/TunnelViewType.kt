package io.github.p1neapplexpress.openflux.data

data class TunnelViewType(
    val tunnel: Tunnel,
    val enabled: Boolean,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val lastInboundTimestamp: Long = 0,
)
