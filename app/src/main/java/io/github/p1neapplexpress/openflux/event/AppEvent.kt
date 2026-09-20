package io.github.p1neapplexpress.openflux.event

sealed interface AppEvent {
    data class LogMessage(val message: String) : AppEvent
    data class ToggleTunnel(val id: Long, val enabled: Boolean) : AppEvent
    data object TransportConnected : AppEvent
    data object TransportDisconnected : AppEvent
    data object VpnRevoked : AppEvent

    /** The OpenFlux process died or never came up; the VPN has been stopped. */
    data class NativeProcessExited(val message: String) : AppEvent
}
