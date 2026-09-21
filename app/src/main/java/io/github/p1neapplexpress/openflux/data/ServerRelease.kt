package io.github.p1neapplexpress.openflux.data

object ServerRelease {
    const val REVISION = 2

    fun updateAvailable(tunnel: Tunnel): Boolean =
        !tunnel.adminHost.isNullOrBlank() && tunnel.serverRevision < REVISION
}
