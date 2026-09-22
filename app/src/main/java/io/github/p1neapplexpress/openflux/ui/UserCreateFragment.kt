package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
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
import io.github.p1neapplexpress.openflux.data.ManagedProfile
import io.github.p1neapplexpress.openflux.data.ManagedUserRequest
import io.github.p1neapplexpress.openflux.data.SavedAdminPassword
import io.github.p1neapplexpress.openflux.data.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
            yandex = MaterialCheckBox(context).apply { text = "Яндекс Документы"; isChecked = current?.transportConnPayload?.isNotEmpty() == true; isEnabled = isChecked; setTextColor(resources.getColor(R.color.text_primary, context.theme)) }
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
        val profiles = buildList {
            if (hysteria.isChecked) server.hysteriaUri?.takeIf(String::isNotBlank)?.let { add(ManagedProfile("Hysteria 2", it)) }
            if (yandex.isChecked && server.transportConnPayload.isNotEmpty()) {
                val clientConfig = server.copy(adminHost = null, adminUser = null, adminPort = null)
                add(ManagedProfile("Яндекс Документы", Json.encodeToString(Tunnel.serializer(), clientConfig)))
            }
        }
        if (profiles.isEmpty()) { status.text = "Выбери хотя бы один установленный протокол"; return }
        if (server.adminHost.isNullOrBlank() || server.adminUser.isNullOrBlank()) { status.text = "Для этого сервера не настроено управление"; return }
        val request = ManagedUserRequest(name, profiles = profiles)
        val host = server.adminHost
        val user = server.adminUser
        val port = server.adminPort ?: 22
        val saved = SavedAdminPassword.read(requireContext(), host, user, port)
        if (saved != null) submit(server, request, saved, false)
        else askPassword(server, request)
    }

    private fun askPassword(server: Tunnel, request: ManagedUserRequest) {
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
                    submit(server, request, value, true)
                }
            }
        }
        dialog.show()
    }

    private fun submit(server: Tunnel, request: ManagedUserRequest, password: String, retry: Boolean) {
        create.isEnabled = false
        status.text = "Создаём пользователя и ключ…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ControlApiClient(server.adminHost!!, server.adminUser!!, server.adminPort ?: 22, password, knownHostsPath()).use { it.createUser(request) }
                }
            }
            result.onSuccess { managed ->
                parentFragmentManager.popBackStackImmediate()
                parentFragmentManager.beginTransaction()
                    .replace(R.id.main, AccessKeyFragment.new(managed.alias, managed.bundle))
                    .addToBackStack("new_access_key")
                    .commit()
            }.onFailure { error ->
                val authFailure = generateSequence(error) { it.cause }.any { it.message.orEmpty().contains("auth", true) }
                if (retry && authFailure) SavedAdminPassword.forget(requireContext(), server.adminHost.orEmpty(), server.adminUser.orEmpty(), server.adminPort ?: 22)
                create.isEnabled = true
                val cause = generateSequence(error) { it.cause }.firstOrNull { !it.message.isNullOrBlank() }
                val detail = cause?.message?.take(180)?.replace(Regex("[\\r\\n]+"), " ")
                status.text = when {
                    retry && authFailure -> "Пароль не подошёл. Попробуй ещё раз."
                    detail.isNullOrBlank() -> "Не удалось создать ключ (${error.javaClass.simpleName}). Проверь SSH-доступ и сервер управления."
                    else -> "Не удалось создать ключ: $detail"
                }
                if (retry && authFailure) askPassword(server, request)
            }
        }
    }

    private fun knownHostsPath() = File(requireContext().filesDir, "ssh_known_hosts").apply { if (!exists()) createNewFile() }.absolutePath
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onNewEvent(ev: io.github.p1neapplexpress.openflux.event.AppEvent) = Unit
}
