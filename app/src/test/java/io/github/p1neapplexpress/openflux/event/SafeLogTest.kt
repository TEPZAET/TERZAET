package io.github.p1neapplexpress.openflux.event

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLogTest {
    @Test
    fun removesAddressesAndSecrets() {
        val safe = SafeLog.message("connect 203.0.113.42:443 key=abcdef0123456789")
        assertFalse(safe.contains("203.0.113.42"))
        assertFalse(safe.contains("abcdef0123456789"))
        assertTrue(safe.contains("скрыто"))
    }

    @Test
    fun turnsTimeoutIntoReadableText() {
        assertTrue(SafeLog.message("read timed out").contains("не ответил"))
    }
}
