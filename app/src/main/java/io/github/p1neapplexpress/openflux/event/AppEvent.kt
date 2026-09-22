package io.github.p1neapplexpress.openflux.event

sealed interface AppEvent {
    data class LogMessage(val message: String) : AppEvent
    data class ToggleTunnel(val id: Long, val enabled: Boolean) : AppEvent
    data object TransportConnected : AppEvent
    data object TransportDisconnected : AppEvent
    data object VpnRevoked : AppEvent
    data class ConnectionStatus(val status: Status) : AppEvent
    data class NativeProcessExited(val message: String) : AppEvent
    data class TransportChanged(val transport: Transport) : AppEvent

    enum class Transport {
        YANDEX,
        HYSTERIA2,
    }

    enum class Status {
        CHECKING,
        WAITING_FOR_NETWORK,
        RESTORING,
        RESTORED,
        UNAVAILABLE,
    }
}
