package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ManagedUserProfiles {
    fun build(
        server: Tunnel,
        includeYandex: Boolean,
        includeHysteria: Boolean,
        serverDocumentUrl: String?,
    ): List<ManagedProfile> = buildList {
        if (includeHysteria) {
            val uri = server.hysteriaUri?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Hysteria 2 не установлена на сервере")
            add(ManagedProfile("Hysteria 2", uri))
        }
        if (includeYandex) {
            val url = serverDocumentUrl?.trim()?.takeIf { it.startsWith("https://") }
                ?: throw IllegalArgumentException("На сервере не найдена ссылка Яндекс Диска")
            val form = TunnelPayload.parse(server.transportType, server.transportConnPayload).copy(url = url)
            val payload = TunnelPayload.build(form)
                ?: throw IllegalArgumentException("Не удалось подготовить Яндекс-профиль")
            val clientConfig = server.copy(
                transportConnPayload = payload,
                adminHost = null,
                adminUser = null,
                adminPort = null,
                serverRevision = 0,
                hysteriaUri = null,
                connectionMode = ConnectionMode.yandex.name,
                autoFallback = false,
            )
            add(ManagedProfile("Яндекс Документы", Json.encodeToString(clientConfig)))
        }
    }
}
