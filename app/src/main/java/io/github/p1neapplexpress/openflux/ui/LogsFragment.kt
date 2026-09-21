package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private val exportedLogs = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fileTime = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.getDefault())
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.appendLine("TERZAET · обезличенный журнал")
                writer.appendLine()
                exportedLogs.forEach(writer::appendLine)
            } ?: error("Не удалось открыть файл")
        }.onSuccess {
            Toast.makeText(requireContext(), "Журнал экспортирован", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(requireContext(), "Не удалось сохранить журнал", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_logs, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        list = view.findViewById(R.id.log_list)
        scrollView = view.findViewById(R.id.log_scroll)
        view.findViewById<TextView>(R.id.btn_clear).setOnClickListener {
            list.removeAllViews()
            exportedLogs.clear()
        }
        view.findViewById<TextView>(R.id.btn_export).setOnClickListener {
            if (exportedLogs.isEmpty()) {
                Toast.makeText(requireContext(), "Журнал пока пуст", Toast.LENGTH_SHORT).show()
            } else {
                exportDocument.launch("TERZAET-log-${fileTime.format(Date())}.txt")
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            EventBus.events.collect { event ->
                if (event is AppEvent.LogMessage) event.message.lineSequence().filter { it.isNotBlank() }.forEach(::appendCard)
            }
        }
    }

    private fun appendCard(raw: String) {
        val lower = raw.lowercase(Locale.ROOT)
        val error = lower.contains("не удалось") || lower.contains("отклонил") || lower.contains("не ответил")
        val success = lower.contains("восстановлено") || lower.contains("запущен") || lower.contains("проверена")
        val title: String
        val explanation: String
        when {
            lower.contains("dns ожидает") -> {
                title = "DNS ожидает канал"
                explanation = "После подключения серверу может понадобиться несколько секунд. TERZAET повторит запрос автоматически."
            }
            lower.contains("переподключается") || lower.contains("восстанавливаем") -> {
                title = "Транспорт переподключается"
                explanation = "Сервер закрыл соединение без ответа. Приложение повторяет подключение."
            }
            lower.contains("передача данных проверена") -> {
                title = "Канал проверен"
                explanation = "Сервер передаёт данные, VPN-интерфейс можно запускать."
            }
            lower.contains("канал запущен") || lower.contains("vpn-интерфейс") -> {
                title = "VPN готов"
                explanation = "Системный туннель запущен и принимает трафик."
            }
            lower.contains("запрос передан") -> {
                title = "Запрос передан"
                explanation = "Приложение отправило соединение через выбранный сервер."
            }
            error -> {
                title = "Не удалось выполнить действие"
                explanation = friendlyReason(lower)
            }
            else -> {
                title = "Состояние подключения"
                explanation = raw
            }
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = TextView(requireContext()).apply {
            text = "$title  ·  ${time.format(Date())}\n$explanation"
            textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setLineSpacing(0f, 1.12f)
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = ContextCompat.getDrawable(requireContext(), when {
                error || lower.contains("timed out") -> R.drawable.bg_status_error
                success -> R.drawable.bg_status_success
                else -> R.drawable.bg_glass_field
            })
            setOnClickListener {
                requireContext().getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TERZAET log", text))
                Toast.makeText(requireContext(), "Лог скопирован", Toast.LENGTH_SHORT).show()
                animate().cancel()
                animate().scaleX(0.98f).scaleY(0.98f).setDuration(80L).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(140L).start() }.start()
            }
        }
        exportedLogs.addLast(card.text.toString())
        while (exportedLogs.size > 120) exportedLogs.removeFirst()
        list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        while (list.childCount > 120) list.removeViewAt(0)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun friendlyReason(lower: String) = when {
        lower.contains("авторизац") -> "Проверьте данные доступа к серверу."
        lower.contains("не ответил") -> "Проверьте интернет и повторите попытку через несколько секунд."
        else -> "Соединение будет проверено повторно."
    }
}
