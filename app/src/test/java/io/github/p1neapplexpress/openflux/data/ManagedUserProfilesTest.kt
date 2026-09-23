package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedUserProfilesTest {
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
