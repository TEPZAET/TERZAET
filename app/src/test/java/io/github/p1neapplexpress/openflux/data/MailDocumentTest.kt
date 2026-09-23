package io.github.p1neapplexpress.openflux.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailDocumentTest {
    @Test
    fun `normalizes public document link`() {
        assertEquals("https://cloud.mail.ru/public/AbC/dEf", MailDocument.canonical("https://cloud.mail.ru/public/AbC/dEf?from=share"))
        assertEquals("AbC/dEf", MailDocument.weblink("https://cloud.mail.ru/public/AbC/dEf"))
    }

    @Test
    fun `rejects unrelated and unsafe links`() {
        assertNull(MailDocument.canonical("https://disk.yandex.ru/i/example"))
        assertNull(MailDocument.canonical("https://cloud.mail.ru.evil.test/public/AbC/dEf"))
        assertNull(MailDocument.canonical("http://cloud.mail.ru/public/AbC/dEf"))
        assertNull(MailDocument.canonical("https://cloud.mail.ru/public/AbC"))
        assertNull(MailDocument.canonical("https://cloud.mail.ru/public/AbC/d'Ef"))
    }
}
