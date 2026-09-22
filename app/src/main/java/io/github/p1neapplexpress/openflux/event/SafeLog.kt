package io.github.p1neapplexpress.openflux.event

import java.util.Locale

object SafeLog {
    private val address = Regex("(?<![A-Za-z0-9])[A-Fa-f0-9:.]{3,}:[0-9]{1,5}")
    private val ipv4 = Regex("(?<![A-Za-z0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![A-Za-z0-9])")
    private val secret = Regex("(?i)(password|token|secret|key|url)\\s*[=:]\\s*\\S+")
    private val hysteriaUri = Regex("(?i)hysteria2(?:\\+realm)?://\\S+")

    fun message(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            "captcha" in lower -> "Яндекс запросил CAPTCHA. Замените документ или IP сервера."
            "подключение восстановлено" in lower -> "Подключение восстановлено"
            "восстанавли" in lower -> "Восстанавливаем соединение"
            "vpn configured" in lower -> "VPN-интерфейс подготовлен"
            "tun2socks running" in lower -> "Защищённый канал запущен"
            "data path verified" in lower -> "Передача данных проверена"
            "dns via tunnel" in lower && ("timed out" in lower || "failed" in lower) -> "DNS ожидает восстановления канала"
            "websocket" in lower && ("error" in lower || "1006" in lower) -> "Транспорт переподключается"
            "auth" in lower && ("fail" in lower || "error" in lower) -> "Сервер отклонил авторизацию"
            "timed out" in lower || "timeout" in lower -> "Сервер не ответил вовремя"
            "failed" in lower || "fatal" in lower || "error" in lower -> "Не удалось выполнить сетевую операцию"
            "socks5" in lower && "connect" in lower -> "Запрос передан через защищённый канал"
            else -> sanitize(raw).take(140).ifBlank { "Состояние подключения обновлено" }
        }
    }

    private fun sanitize(raw: String): String = raw
        .replace(secret, "\$1: скрыто")
        .replace(hysteriaUri, "Hysteria 2: скрыто")
        .replace(address, "адрес скрыт")
        .replace(ipv4, "IP скрыт")
        .replace(Regex("\\[[^]]{1,32}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
