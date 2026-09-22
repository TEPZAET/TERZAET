package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.Serializable

@Serializable
data class Tunnel(
    val id: Long,
    val name: String,
    val transportType: String,
    val transportConnPayload: List<String>,
    val encryptionKey: String? = null,
    val adminHost: String? = null,
    val adminUser: String? = null,
    val adminPort: Int? = null,
    val serverRevision: Int = 0,
    val hysteriaUri: String? = null,
    val connectionMode: String = ConnectionMode.auto.name,
    val autoFallback: Boolean = true,
)
