package io.github.p1neapplexpress.openflux.data

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelBundleTest {

    @Test
    fun `bundle imports yandex and hysteria profiles without admin credentials`() {
        val yandex = Tunnel(
            id = 12L,
            name = "Server",
            transportType = TransportType.yandex.name,
            transportConnPayload = TunnelPayload.build(TunnelPayload.Form(TransportType.yandex, "https://disk.yandex.ru/d/x"))!!,
            adminHost = "10.0.0.1",
            adminUser = "root",
            adminPort = 22,
        )
        val bundle = TunnelBundle(
            userId = "abcdef123456",
            profiles = listOf(
                TunnelBundleProfile("Яндекс Документы", Json.encodeToString(yandex)),
                TunnelBundleProfile("Hysteria 2", "hysteria2://secret@example.com:443/?insecure=1"),
            ),
        )
        val raw = encode(bundle)

        val imported = TunnelBundleParser.parse(raw, id = 99L)

        assertEquals(99L, imported.id)
        assertEquals(yandex.transportConnPayload, imported.transportConnPayload)
        assertEquals("hysteria2://secret@example.com:443/?insecure=1", imported.hysteriaUri)
        assertEquals(ConnectionMode.auto.name, imported.connectionMode)
        assertNull(imported.adminHost)
        assertNull(imported.adminUser)
        assertEquals(null, imported.adminPort)
    }

    @Test
    fun `hy2-only bundle imports as hysteria tunnel`() {
        val raw = encode(TunnelBundle(userId = "12345678", profiles = listOf(
            TunnelBundleProfile("Hysteria 2", "hysteria2://secret@example.com:443/?insecure=1"),
        )))

        val imported = TunnelBundleParser.parse(raw, id = 7L)

        assertEquals(7L, imported.id)
        assertTrue(imported.transportConnPayload.isEmpty())
        assertEquals(ConnectionMode.hysteria2.name, imported.connectionMode)
        assertEquals("hysteria2://secret@example.com:443/?insecure=1", imported.hysteriaUri)
    }

    @Test
    fun `legacy tunnel json remains importable`() {
        val tunnel = Tunnel(1L, "Legacy", TransportType.yandex.name, emptyList())

        val imported = TunnelBundleParser.parse(Json.encodeToString(tunnel), id = 5L)

        assertEquals(5L, imported.id)
        assertEquals("Legacy", imported.name)
    }

    private fun encode(bundle: TunnelBundle): String {
        val payload = Json.encodeToString(bundle).toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        return "terzaet://$encoded.signature"
    }
}
