package io.github.p1neapplexpress.openflux.data

sealed interface TunnelState {
    val tunnel: Tunnel?
    val color: Int

    data object Idle : TunnelState {
        override val tunnel: Tunnel? = null
        override val color: Int = 0xFF6B7280.toInt()
    }

    data class Connecting(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFFBBF24.toInt()
    }

    data class StartingTransport(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFFBBF24.toInt()
    }

    data class StartingTun2Socks(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFFBBF24.toInt()
    }

    data class Checking(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFFBBF24.toInt()
    }

    data class Running(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFF22C55E.toInt()
    }

    data class Restoring(override val tunnel: Tunnel, val attempt: Int) : TunnelState {
        override val color: Int = 0xFFF59E0B.toInt()
    }

    data class Unavailable(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFEF4444.toInt()
    }

    data class Stopping(override val tunnel: Tunnel?) : TunnelState {
        override val color: Int = 0xFF6B7280.toInt()
    }

    data class Error(val message: String) : TunnelState {
        override val tunnel: Tunnel? = null
        override val color: Int = 0xFFEF4444.toInt()
    }

    val isActive: Boolean
        get() = this is Connecting ||
                this is StartingTransport ||
                this is StartingTun2Socks ||
                this is Checking ||
                this is Restoring ||
                this is Unavailable ||
                this is Stopping ||
                this is Running
}
