package io.github.p1neapplexpress.openflux.data

import io.github.p1neapplexpress.openflux.data.TunnelPayload.Form
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelPayloadTest {

    @Test
    fun `url transports store role transport and url`() {
        assertEquals(
            listOf("--role", "client", "--transport", "mailru", "--url", "AbC/dEf"),
            TunnelPayload.build(Form(TransportType.mailru, url = "AbC/dEf")),
        )
    }

    @Test
    fun `max stores token and uid under the oneme transport name`() {
        assertEquals(
            listOf("--role", "client", "--transport", "oneme", "--maxToken", "tok", "--maxUid", "42"),
            TunnelPayload.build(Form(TransportType.max, maxToken = "tok", maxUid = "42")),
        )
    }

    @Test
    fun `legacy codec and debug are appended`() {
        assertEquals(
            listOf("--role", "client", "--transport", "yandex", "--url", "u", "--codec", "legacy", "--debug"),
            TunnelPayload.build(Form(TransportType.yandex, url = "u", legacyCodec = true, debug = true)),
        )
    }

    @Test
    fun `missing transport fields give null`() {
        assertNull(TunnelPayload.build(Form(TransportType.cupsonline)))
        assertNull(TunnelPayload.build(Form(TransportType.max, maxToken = "tok")))
        assertNull(TunnelPayload.build(Form(TransportType.max, maxUid = "42")))
    }

    @Test
    fun `parse reads payloads saved by older versions`() {
        val payload = listOf("--client", "--transport", "vyandex", "--url", "https://disk.yandex.ru/d/x", "--debug")
        assertEquals(
            Form(TransportType.vyandex, url = "https://disk.yandex.ru/d/x", debug = true),
            TunnelPayload.parse("vyandex", payload),
        )
    }

    @Test
    fun `parse inverts build for every transport`() {
        val forms = listOf(
            Form(TransportType.yandex, url = "https://docs.yandex.ru/docs/view?url=x"),
            Form(TransportType.vyandex, url = "https://disk.yandex.ru/d/y", debug = true),
            Form(TransportType.max, maxToken = "tok", maxUid = "42", legacyCodec = true),
            Form(TransportType.cupsonline, url = "WyJhIiwiYiJd"),
            Form(TransportType.mailru, url = "https://cloud.mail.ru/public/AbC/dEf", legacyCodec = true, debug = true),
        )
        for (form in forms) {
            val payload = TunnelPayload.build(form)!!
            assertEquals(form, TunnelPayload.parse(form.transport.name, payload))
        }
    }

    @Test
    fun `value reads both flag spellings`() {
        assertEquals("legacy", TunnelPayload.value(listOf("--codec=legacy"), "codec"))
        assertEquals("legacy", TunnelPayload.value(listOf("-codec", "legacy"), "codec"))
        assertEquals("", TunnelPayload.value(listOf("--url"), "url"))
        assertEquals("", TunnelPayload.value(listOf("--transport", "yandex"), "url"))
    }
}
