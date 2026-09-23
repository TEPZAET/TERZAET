package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedUserProfilesTest {
    @Test
    fun `mail user key retains mail transport and current public link`() {
        val server = Tunnel(
            id = 2L,
            name = "Mail server",
            transportType = TransportType.mailru.name,
            transportConnPayload = TunnelPayload.build(TunnelPayload.Form(TransportType.mailru, "https://cloud.mail.ru/public/AbC/old"))!!,
            adminHost = "10.0.0.2",
            adminUser = "root",
        )

        val profiles = ManagedUserProfiles.build(server, true, false, "https://cloud.mail.ru/public/AbC/new")
        val imported = Json.decodeFromString<Tunnel>(profiles.single().uri)

        assertEquals("Mail Документы", profiles.single().name)
        assertEquals(TransportType.mailru.name, imported.transportType)
        assertEquals("https://cloud.mail.ru/public/AbC/new", TunnelPayload.parse(imported.transportType, imported.transportConnPayload).url)
        assertNull(imported.adminHost)
        assertNull(imported.adminUser)
    }

    @Test
    fun `user key gets current server URL but no admin access or hidden Hysteria`() {
        val server = Tunnel(
            id = 1L,
            name = "Server",
            transportType = TransportType.yandex.name,
            transportConnPayload = TunnelPayload.build(TunnelPayload.Form(TransportType.yandex, "https://disk.yandex.ru/i/old"))!!,
            adminHost = "10.0.0.1",
            adminUser = "root",
            hysteriaUri = "hysteria2://secret@example.com:443/",
        )

        val profiles = ManagedUserProfiles.build(server, true, false, "https://disk.yandex.ru/i/current")
        val imported = Json.decodeFromString<Tunnel>(profiles.single().uri)

        assertEquals("https://disk.yandex.ru/i/current", TunnelPayload.parse(imported.transportType, imported.transportConnPayload).url)
        assertNull(imported.adminHost)
        assertNull(imported.adminUser)
        assertNull(imported.hysteriaUri)
        assertEquals(ConnectionMode.yandex.name, imported.connectionMode)
    }
}
