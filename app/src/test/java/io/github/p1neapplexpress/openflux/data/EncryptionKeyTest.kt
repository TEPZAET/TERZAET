package io.github.p1neapplexpress.openflux.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionKeyTest {

    @Test
    fun `needs 16 bytes after trimming like the Go side`() {
        assertTrue(EncryptionKey.isValid("1234567890123456"))
        assertTrue(EncryptionKey.isValid(" 1234567890123456\n"))
        assertFalse(EncryptionKey.isValid("123456789012345"))
        assertFalse(EncryptionKey.isValid("  123456789012345  "))
        assertFalse(EncryptionKey.isValid(""))
    }

    @Test
    fun `counts utf8 bytes not characters`() {
        // 8 Cyrillic letters are 16 bytes, which OpenFlux accepts.
        assertTrue(EncryptionKey.isValid("ключключ"))
        assertFalse(EncryptionKey.isValid("ключклю"))
    }

    @Test
    fun `normalize trims surrounding whitespace only`() {
        assertEquals("a b c", EncryptionKey.normalize("\t a b c \r\n"))
    }
}
