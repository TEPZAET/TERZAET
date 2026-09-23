package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.ControlApiClient
import io.github.p1neapplexpress.openflux.data.ManagedUserProfiles
import io.github.p1neapplexpress.openflux.data.ManagedUserRequest
import io.github.p1neapplexpress.openflux.data.SavedAdminPassword
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelPayload
import io.github.p1neapplexpress.openflux.data.TransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UserCreateFragment : BaseFragment() {
    companion object {
        fun new(tunnel: Tunnel) = UserCreateFragment().apply { arguments = Bundle().apply { putLong("tunnel", tunnel.id) } }
    }

    private val vm: TunnelsViewModel by activityViewModels()
    private lateinit var alias: TextInputEditText
    private lateinit var yandex: MaterialCheckBox
    private lateinit var hysteria: MaterialCheckBox
    private lateinit var status: TextView
    private lateinit var create: MaterialButton
    private val tunnel get() = vm.tunnels.value.firstOrNull { it.tunnel.id == requireArguments().getLong("tunnel") }?.tunnel

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?) =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), dp(26))
            setBackgroundResource(R.drawable.bg_screen_ambient)
            addView(TextView(context).apply { text = "Новый пользователь"; textSize = 25f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(R.color.text_primary, context.theme)) })
            addView(TextView(context).apply { text = tunnel?.name.orEmpty(); textSize = 13f; setTextColor(resources.getColor(R.color.text_secondary, context.theme)); setPadding(0, dp(4), 0, dp(18)) })
            val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); setBackgroundResource(R.drawable.glass_panel) }
            alias = TextInputEditText(context).apply {
                hint = "Имя пользователя"
                maxLines = 1
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_NONE
                setOnEditorActionListener { _, _, _ -> true }
                setOnKeyListener { _, keyCode, event -> keyCode == KeyEvent.KEYCODE_ENTER }
            }
            form.addView(TextInputLayout(context).apply { hint = "Имя"; setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE); addView(alias) })
            form.addView(TextView(context).apply { text = "Выбери протоколы, которые войдут в пользовательский ключ"; textSize = 14f; setTextColor(resources.getColor(R.color.text_secondary, context.theme)); setPadding(0, dp(18), 0, dp(8)) })
            val current = tunnel
            val yandexUrlAvailable = current?.let { TunnelPayload.parse(it.transportType, it.transportConnPayload).url.isNotBlank() } == true
            val documentName = if (current?.transportType == TransportType.mailru.name) "Mail Документы" else "Яндекс Документы"
            yandex = MaterialCheckBox(context).apply { text = documentName; isChecked = yandexUrlAvailable; isEnabled = yandexUrlAvailable; setTextColor(resources.getColor(R.color.text_primary, context.theme)) }
            hysteria = MaterialCheckBox(context).apply { text = "Hysteria 2"; isChecked = !current?.hysteriaUri.isNullOrBlank(); isEnabled = isChecked; setTextColor(resources.getColor(R.color.text_primary, context.theme)) }
            form.addView(yandex)
            form.addView(hysteria)
            form.addView(TextView(context).apply { text = "Доступны только протоколы, установленные на этом сервере."; textSize = 12f; setTextColor(resources.getColor(R.color.text_tertiary, context.theme)); setPadding(0, dp(6), 0, 0) })
            addView(form)
            status = TextView(context).apply { text = ""; textSize = 13f; setTextColor(resources.getColor(R.color.text_secondary, context.theme)); setPadding(dp(4), dp(12), dp(4), dp(4)) }
            addView(status)
            create = MaterialButton(context).apply {
                text = "Создать ключ доступа"
                isAllCaps = false
                setTextColor(android.graphics.Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.colorPrimary, context.theme))
                setOnClickListener { createUser() }
            }
            addView(create, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })
        }

    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); UiAppearance.apply(view) }

    private fun createUser() {
        val server = tunnel ?: return
        val name = alias.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) { alias.error = "Укажи имя"; return }
        if (yandex.isChecked && TunnelPayload.parse(server.transportType, server.transportConnPayload).url.isBlank()) {
            status.text = "В конфигурации сервера не найдена ссылка документа. Добавь её в настройках сервера."
            return
        }
        val includeYandex = yandex.isChecked
        val includeHysteria = hysteria.isChecked
        if (!includeYandex && !includeHysteria) { status.text = "Выбери хотя бы один установленный протокол"; return }
        if (server.adminHost.isNullOrBlank() || server.adminUser.isNullOrBlank()) { status.text = "Для этого сервера не настроено управление"; return }
        val host = server.adminHost
        val user = server.adminUser
        val port = server.adminPort ?: 22
        val saved = SavedAdminPassword.read(requireContext(), host, user, port)
        if (saved != null) submit(server, name, includeYandex, includeHysteria, saved)
        else askPassword(server, name, includeYandex, includeHysteria)
    }

    private fun askPassword(server: Tunnel, name: String, includeYandex: Boolean, includeHysteria: Boolean) {
        val host = server.adminHost ?: return
        val user = server.adminUser ?: return
        val port = server.adminPort ?: 22
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        box.addView(TextView(requireContext()).apply { text = "Пароль нужен для защищённого подключения к серверу."; textSize = 13f; setTextColor(resources.getColor(R.color.text_secondary, context.theme)); setPadding(0, 0, 0, dp(10)) })
        val password = EditText(requireContext()).apply { hint = "Пароль VDS"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; maxLines = 1; imeOptions = EditorInfo.IME_ACTION_NONE; setOnEditorActionListener { _, _, _ -> true }; setOnKeyListener { _, code, _ -> code == KeyEvent.KEYCODE_ENTER } }
        box.addView(TextInputLayout(requireContext()).apply { hint = "Пароль VDS"; setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE); addView(password) })
        val remember = MaterialCheckBox(requireContext()).apply { text = "Сохранить пароль на этом телефоне"; isChecked = true }
        box.addView(remember)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Подтверди доступ к серверу").setView(box).setNegativeButton("Отмена", null).setPositiveButton("Продолжить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = password.text.toString()
                if (value.isBlank()) password.error = "Введи пароль"
                else {
                    if (remember.isChecked) SavedAdminPassword.save(requireContext(), host, user, port, value)
                    dialog.dismiss()
                    submit(server, name, includeYandex, includeHysteria, value)
                }
            }
        }
        dialog.show()
    }

    private fun submit(server: Tunnel, name: String, includeYandex: Boolean, includeHysteria: Boolean, password: String) {
        create.isEnabled = false
        status.text = "Создаём пользователя и ключ…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ControlApiClient(server.adminHost!!, server.adminUser!!, server.adminPort ?: 22, password, knownHostsPath()).use { client ->
                        val documentUrl = if (includeYandex) client.serverDocumentUrl() else null
                        val profiles = ManagedUserProfiles.build(server, includeYandex, includeHysteria, documentUrl)
                        client.createUser(ManagedUserRequest(name, profiles = profiles))
                    }
                }
            }
            result.onSuccess { managed ->
                val manager = parentFragmentManager
                if (manager.isStateSaved) {
                    create.isEnabled = true
                    status.text = "Пользователь создан. Открой список пользователей, чтобы показать его ключ."
                    return@onSuccess
                }
                val toastContext = requireContext().applicationContext
                manager.popBackStackImmediate("user_create", androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        if (manager.isStateSaved) return@post
                        manager.beginTransaction()
                            .replace(R.id.main, AccessKeyFragment.new(managed.alias, managed.bundle))
                            .addToBackStack("new_access_key")
                            .commit()
                    }.onFailure {
                        android.widget.Toast.makeText(toastContext, "Пользователь создан. Его ключ можно открыть из списка пользователей.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure { error ->
                val authFailure = generateSequence(error) { it.cause }.any { it.message.orEmpty().contains("auth", true) }
                if (authFailure) SavedAdminPassword.forget(requireContext(), server.adminHost.orEmpty(), server.adminUser.orEmpty(), server.adminPort ?: 22)
                create.isEnabled = true
                val cause = generateSequence(error) { it.cause }.firstOrNull { !it.message.isNullOrBlank() }
                val detail = cause?.message?.take(180)?.replace(Regex("[\\r\\n]+"), " ")
                status.text = when {
                    authFailure -> "Пароль не подошёл. Попробуй ещё раз."
                    detail.isNullOrBlank() -> "Не удалось создать ключ (${error.javaClass.simpleName}). Проверь SSH-доступ и сервер управления."
                    else -> "Не удалось создать ключ: $detail"
                }
                if (authFailure) askPassword(server, name, includeYandex, includeHysteria)
            }
        }
    }

    private fun knownHostsPath() = File(requireContext().filesDir, "ssh_known_hosts").apply { if (!exists()) createNewFile() }.absolutePath
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onNewEvent(ev: io.github.p1neapplexpress.openflux.event.AppEvent) = Unit
}
