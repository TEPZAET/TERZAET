package io.github.p1neapplexpress.openflux.data

import java.net.URI

object MailDocument {
    fun weblink(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme != "https" || uri.host != "cloud.mail.ru" || uri.userInfo != null || uri.port != -1) return null
        val segments = uri.path.orEmpty().trim('/').split('/')
        if (segments.size != 3 || segments[0] != "public" || segments.drop(1).any { !it.matches(Regex("[A-Za-z0-9_-]+")) }) return null
        segments.drop(1).joinToString("/")
    }.getOrNull()

    fun canonical(value: String): String? = weblink(value)?.let { "https://cloud.mail.ru/public/$it" }
}
