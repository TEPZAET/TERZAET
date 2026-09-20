package io.github.p1neapplexpress.openflux.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTypeTest {

    @Test
    fun `from accepts stored names and cli names`() {
        assertEquals(TransportType.max, TransportType.from("max"))
        assertEquals(TransportType.max, TransportType.from("oneme"))
        assertEquals(TransportType.max, TransportType.from("MAX"))
        assertEquals(TransportType.cupsonline, TransportType.from("cupsonline"))
        assertEquals(TransportType.mailru, TransportType.from("mailru"))
    }

    @Test
    fun `from falls back to yandex`() {
        assertEquals(TransportType.yandex, TransportType.from("carrier-pigeon"))
    }

    @Test
    fun `only max is configured without url`() {
        assertFalse(TransportType.max.usesUrl)
        TransportType.entries.filter { it != TransportType.max }.forEach { assertTrue(it.usesUrl) }
    }
}
