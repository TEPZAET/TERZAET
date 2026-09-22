package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionMode {
    auto,
    yandex,
    hysteria2;

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: auto
    }
}
