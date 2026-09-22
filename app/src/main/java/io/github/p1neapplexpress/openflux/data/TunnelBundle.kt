package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.random.Random

@Serializable
data class TunnelBundle(
    @SerialName("v") val version: Int = 1,
    @SerialName("u") val userId: String = "",
    @SerialName("p") val profiles: List<TunnelBundleProfile> = emptyList(),
    @SerialName("e") val expires: Long? = null,
)

@Serializable
data class TunnelBundleProfile(
    val name: String = "",
    val uri: String = "",
)

object TunnelBundleParser {
    private const val PREFIX = "terzaet://"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, id: Long = Random(System.nanoTime()).nextLong()): Tunnel {
        val value = raw.trim()
        if (value.startsWith("{")) return json.decodeFromString<Tunnel>(value).withoutAdmin(id)
        require(value.startsWith(PREFIX, ignoreCase = true)) { "Unsupported TERZAET key" }

        val token = value.substring(PREFIX.length)
        val separator = token.indexOf('.')
        require(separator > 0 && separator < token.lastIndex) { "Malformed TERZAET key" }
        val encodedPayload = token.substring(0, separator)
        require(token.substring(separator + 1).isNotEmpty()) { "Missing TERZAET key signature" }
        val payload = String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8)
        val bundle = json.decodeFromString<TunnelBundle>(payload)
        require(bundle.version == 1) { "Unsupported TERZAET key version" }

        val yandex = bundle.profiles.asSequence()
            .mapNotNull { profile -> runCatching { json.decodeFromString<Tunnel>(profile.uri) }.getOrNull() }
            .firstOrNull()
        val hysteria = bundle.profiles.firstOrNull { it.uri.startsWith("hysteria2://", ignoreCase = true) }?.uri
        require(yandex != null || hysteria != null) { "TERZAET key has no supported profile" }

        val base = yandex ?: Tunnel(
            id = id,
            name = "TERZAET · ${bundle.userId.take(8)}".trimEnd(' ', '·'),
            transportType = TransportType.yandex.name,
            transportConnPayload = emptyList(),
        )
        return base.copy(
            id = id,
            adminHost = null,
            adminUser = null,
            adminPort = null,
            serverRevision = 0,
            hysteriaUri = hysteria ?: base.hysteriaUri,
            connectionMode = when {
                base.transportConnPayload.isEmpty() && hysteria != null -> ConnectionMode.hysteria2.name
                hysteria != null -> ConnectionMode.auto.name
                else -> ConnectionMode.yandex.name
            },
            autoFallback = !hysteria.isNullOrBlank() && base.transportConnPayload.isNotEmpty(),
        )
    }

    private fun Tunnel.withoutAdmin(id: Long): Tunnel = copy(
        id = id,
        adminHost = null,
        adminUser = null,
        adminPort = null,
        serverRevision = 0,
    )
}
