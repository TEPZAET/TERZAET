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
                ?: throw IllegalArgumentException("На сервере не найдена ссылка документа")
            val form = TunnelPayload.parse(server.transportType, server.transportConnPayload).copy(url = url)
            val payload = TunnelPayload.build(form)
                ?: throw IllegalArgumentException("Не удалось подготовить профиль документа")
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
            val name = if (form.transport == TransportType.mailru) "Mail Документы" else "Яндекс Документы"
            add(ManagedProfile(name, Json.encodeToString(clientConfig)))
        }
    }
}
