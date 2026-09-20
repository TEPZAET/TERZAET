package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : BaseFragment() {
    private lateinit var list: LinearLayout
    private lateinit var scrollView: ScrollView
    private var autoScroll = true
    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onNewEvent(ev: AppEvent) = Unit

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_logs, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        list = view.findViewById(R.id.log_list)
        scrollView = view.findViewById(R.id.log_scroll)
        view.findViewById<TextView>(R.id.btn_clear).setOnClickListener { list.removeAllViews() }
        val autoButton = view.findViewById<TextView>(R.id.btn_autoscroll)
        autoButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.log_green))
        autoButton.setOnClickListener {
            autoScroll = !autoScroll
            autoButton.setTextColor(ContextCompat.getColor(requireContext(), if (autoScroll) R.color.log_green else R.color.log_gray))
            if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            EventBus.events.collect { event ->
                if (event is AppEvent.LogMessage) event.message.lineSequence().filter { it.isNotBlank() }.forEach(::appendCard)
            }
        }
    }

    private fun appendCard(raw: String) {
        val lower = raw.lowercase(Locale.ROOT)
        val error = lower.contains("fatal") || lower.contains("error") || lower.contains("failed")
        val success = lower.contains("data path verified") || lower.contains("vpn configured") ||
            lower.contains("tun2socks running") || lower.contains("openflux is up")
        val title: String
        val explanation: String
        when {
            lower.contains("dns via tunnel") && lower.contains("timed out") -> {
                title = "DNS ожидает канал"
                explanation = "После подключения серверу может понадобиться несколько секунд. TERZAET повторит запрос автоматически."
            }
            lower.contains("websocket") && (lower.contains("1006") || lower.contains("read error")) -> {
                title = "Транспорт переподключается"
                explanation = "Сервер закрыл соединение без ответа. Приложение повторяет подключение."
            }
            lower.contains("data path verified") -> {
                title = "Канал проверен"
                explanation = "Сервер передаёт данные, VPN-интерфейс можно запускать."
            }
            lower.contains("tun2socks running") || lower.contains("vpn configured") -> {
                title = "VPN готов"
                explanation = "Системный туннель запущен и принимает трафик."
            }
            lower.contains("socks5") && lower.contains("connect") -> {
                title = "Запрос передан"
                explanation = "Приложение отправило соединение через выбранный сервер."
            }
            error -> {
                title = "Не удалось выполнить действие"
                explanation = friendlyReason(lower)
            }
            else -> {
                title = "Состояние подключения"
                explanation = "Компоненты TERZAET обновили состояние."
            }
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = TextView(requireContext()).apply {
            text = "$title  ·  ${time.format(Date())}\n$explanation\n\nТехнические сведения: $raw"
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setLineSpacing(0f, 1.12f)
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = ContextCompat.getDrawable(requireContext(), when {
                error || lower.contains("timed out") -> R.drawable.bg_status_error
                success -> R.drawable.bg_status_success
                else -> R.drawable.bg_glass_field
            })
        }
        list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        while (list.childCount > 120) list.removeViewAt(0)
        if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun friendlyReason(lower: String) = when {
        lower.contains("timed out") -> "Сервер не ответил вовремя. Проверьте интернет и доступность документа."
        lower.contains("authentication") || lower.contains("auth fail") -> "Сервер отклонил логин или пароль."
        lower.contains("unknownhost") -> "Адрес сервера не найден. Проверьте IP или домен."
        lower.contains("refused") -> "Сервер доступен, но нужная служба пока не принимает соединения."
        else -> "Операция остановлена. Технические сведения ниже помогут определить причину."
    }
}
