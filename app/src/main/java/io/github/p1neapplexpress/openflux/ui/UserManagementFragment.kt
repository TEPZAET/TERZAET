package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.p1neapplexpress.openflux.data.ControlApiClient
import io.github.p1neapplexpress.openflux.data.ManagedProfile
import io.github.p1neapplexpress.openflux.data.ManagedUser
import io.github.p1neapplexpress.openflux.data.ManagedUserRequest
import io.github.p1neapplexpress.openflux.data.SavedAdminPassword
import io.github.p1neapplexpress.openflux.data.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class UserManagementFragment : BaseFragment() {
    private val vm: TunnelsViewModel by activityViewModels()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private val knownUsers = mutableListOf<ManagedUser>()
    private var tunnelId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); tunnelId = requireArguments().getLong("tunnel") }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val scroller = androidx.core.widget.NestedScrollView(context).apply { setBackgroundResource(io.github.p1neapplexpress.openflux.R.drawable.bg_screen_ambient); isFillViewport = true }
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 42, 22, 28) }
        scroller.addView(content)
        val hero = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(20)); setBackgroundResource(io.github.p1neapplexpress.openflux.R.drawable.glass_panel) }
        val emblem = ImageView(context).apply {
            setImageResource(io.github.p1neapplexpress.openflux.R.drawable.ic_admin_users)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; colors = intArrayOf(Color.rgb(88, 93, 100), Color.rgb(52, 56, 62)) }
        }
        hero.addView(emblem, LinearLayout.LayoutParams(dp(58), dp(58)))
        val intro = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        intro.addView(TextView(context).apply { text = "ДОСТУП К СЕРВЕРУ"; textSize = 10f; letterSpacing = .08f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme)) })
        intro.addView(TextView(context).apply { text = "Пользователи"; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_primary, context.theme)) })
        intro.addView(TextView(context).apply { text = currentTunnel()?.name.orEmpty(); textSize = 13f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme)) })
        hero.addView(intro, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(hero)
        content.addView(TextView(context).apply { text = "ПРОФИЛИ ПОДКЛЮЧЕНИЯ"; textSize = 11f; letterSpacing = .08f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_tertiary, context.theme)); setPadding(dp(4), dp(24), dp(4), dp(10)) })
        val statusCard = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(13), dp(16), dp(13)); setBackgroundResource(io.github.p1neapplexpress.openflux.R.drawable.bg_glass_field) }
        statusCard.addView(TextView(context).apply { text = "●"; textSize = 13f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.state_running, context.theme)) })
        status = TextView(context).apply { text = "Пароль запрашивается для каждого действия. Лимиты и отзыв сохраняются для учёта, но ещё не применяются транспортом."; textSize = 13f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme)); setPadding(dp(10), 0, 0, 0) }
        statusCard.addView(status, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(statusCard)
        val add = MaterialButton(context).apply {
            text = "Добавить пользователя"; isAllCaps = false; textSize = 15f
            setIconResource(io.github.p1neapplexpress.openflux.R.drawable.ic_user_add); iconPadding = dp(10)
            backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(io.github.p1neapplexpress.openflux.R.color.colorPrimary, context.theme))
            setOnClickListener { currentTunnel()?.let { open(UserCreateFragment.new(it)) } }
        }
        content.addView(add, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(14) })
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }; content.addView(list)
        return scroller
    }

    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); UiAppearance.apply(view); loadUsers() }

    private fun showBundle(bundle: String) {
        val alias = knownUsers.firstOrNull { it.bundle == bundle }?.alias.orEmpty()
        parentFragmentManager.beginTransaction().replace(io.github.p1neapplexpress.openflux.R.id.main, AccessKeyFragment.new(alias, bundle)).addToBackStack("access_key").commit()
    }

    private fun open(fragment: BaseFragment) {
        parentFragmentManager.beginTransaction().replace(io.github.p1neapplexpress.openflux.R.id.main, fragment).addToBackStack("user_create").commit()
    }

    private fun loadUsers() {
        val tunnel = currentTunnel() ?: return
        if (tunnel.adminHost.isNullOrBlank() || tunnel.adminUser.isNullOrBlank()) { status.text = "Для этого сервера нет данных администратора"; return }
        runWithAdminPassword(tunnel, "Открыть пользователей", action = { secret ->
            ControlApiClient(tunnel.adminHost, tunnel.adminUser, tunnel.adminPort ?: 22, secret, knownHostsPath()).use { it.listUsers() }
        }, onSuccess = { users ->
            render(users)
            status.text = "Список профилей обновлён"
        })
    }

    private fun render(users: List<ManagedUser>) {
        knownUsers.clear()
        knownUsers.addAll(users)
        list.removeAllViews()
        val users = knownUsers
        if (users.isEmpty()) {
            list.addView(TextView(requireContext()).apply { text = "Пользователей пока нет"; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme)) })
            return
        }
        users.forEach { user ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18)); setBackgroundResource(io.github.p1neapplexpress.openflux.R.drawable.glass_panel)
                alpha = 0f; translationY = dp(12).toFloat()
            }
            val heading = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val avatar = TextView(requireContext()).apply {
                text = user.alias.take(1).uppercase(); textSize = 17f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_primary, context.theme))
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.bg_card_stroke, context.theme)) }
            }
            heading.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))
            val identity = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            identity.addView(TextView(requireContext()).apply { text = user.alias; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_primary, context.theme)) })
            identity.addView(TextView(requireContext()).apply { text = if (user.revokedAt == null) "●  Профиль активен · без enforced-лимитов" else "Отметка отзыва сохранена · ключ не заблокирован протоколом"; textSize = 12f; setTextColor(resources.getColor(if (user.revokedAt == null) io.github.p1neapplexpress.openflux.R.color.state_running else io.github.p1neapplexpress.openflux.R.color.text_tertiary, context.theme)); setPadding(0, dp(3), 0, 0) })
            heading.addView(identity, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(heading)
            row.addView(TextView(requireContext()).apply { text = "Создан: ${formatDate(user.createdAt)} · Последнее использование: сервер не отслеживает"; textSize = 11f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_tertiary, context.theme)); setPadding(dp(54), dp(5), 0, 0) })
            val copy = MaterialButton(requireContext()).apply { text = "Показать ключ и QR"; isAllCaps = false; setIconResource(io.github.p1neapplexpress.openflux.R.drawable.ic_key); iconPadding = dp(8); setOnClickListener { showBundle(user.bundle) } }
            row.addView(copy, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
            val actions = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            if (user.revokedAt == null) actions.addView(MaterialButton(requireContext()).apply {
                text = "Отметить отзыв"; isAllCaps = false; textSize = 12f
                setOnClickListener { askPasswordForAction(user.id, false) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            actions.addView(MaterialButton(requireContext()).apply {
                text = "Удалить"; isAllCaps = false; textSize = 12f
                setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.state_error, context.theme))
                backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(io.github.p1neapplexpress.openflux.R.color.bg_deep, context.theme))
                setOnClickListener { askPasswordForAction(user.id, true) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (user.revokedAt == null) marginStart = dp(8) })
            row.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            list.addView(row)
            row.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun askPasswordForAction(id: String, delete: Boolean) {
        val tunnel = currentTunnel() ?: return
        runWithAdminPassword(tunnel, if (delete) "Удалить профиль" else "Отметить профиль", action = { secret ->
            ControlApiClient(tunnel.adminHost!!, tunnel.adminUser!!, tunnel.adminPort ?: 22, secret, knownHostsPath()).use {
                if (delete) it.deleteUser(id) else it.revokeUser(id)
            }
        }, onSuccess = {
            status.text = if (delete) "Профиль удалён из списка; ранее выданный ключ всё ещё действует" else "Отметка сохранена; ранее выданный ключ всё ещё действует"
            loadUsers()
        })
    }

    private fun <T> runWithAdminPassword(tunnel: Tunnel, title: String, action: (String) -> T, onSuccess: (T) -> Unit) {
        val host = tunnel.adminHost ?: return
        val user = tunnel.adminUser ?: return
        val port = tunnel.adminPort ?: 22
        val saved = SavedAdminPassword.read(requireContext(), host, user, port)
        if (saved != null) {
            performAdminAction(tunnel, saved, title, action, onSuccess, retryAfterAuth = true)
        } else {
            showPasswordDialog(tunnel, title) { password, remember ->
                if (remember) SavedAdminPassword.save(requireContext(), host, user, port, password)
                performAdminAction(tunnel, password, title, action, onSuccess, retryAfterAuth = false)
            }
        }
    }

    private fun <T> performAdminAction(
        tunnel: Tunnel,
        password: String,
        title: String,
        action: (String) -> T,
        onSuccess: (T) -> Unit,
        retryAfterAuth: Boolean,
    ) {
        status.text = "$title…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { action(password) } }
            result.onSuccess(onSuccess).onFailure { error ->
                val host = tunnel.adminHost.orEmpty()
                val user = tunnel.adminUser.orEmpty()
                val port = tunnel.adminPort ?: 22
                if (retryAfterAuth && isAuthenticationError(error)) {
                    SavedAdminPassword.forget(requireContext(), host, user, port)
                    showPasswordDialog(tunnel, "Пароль VDS изменился") { fresh, remember ->
                        if (remember) SavedAdminPassword.save(requireContext(), host, user, port, fresh)
                        performAdminAction(tunnel, fresh, title, action, onSuccess, retryAfterAuth = false)
                    }
                } else {
                    status.text = readableError(error)
                }
            }
        }
    }

    private fun showPasswordDialog(tunnel: Tunnel, title: String, onPassword: (String, Boolean) -> Unit) {
        val context = requireContext()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        panel.addView(TextView(context).apply {
            text = "Пароль используется для защищённого SSH-подключения. Его можно сохранить в зашифрованном хранилище телефона; приложение не показывает сохранённое значение."
            textSize = 13f
            setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme))
            setPadding(0, 0, 0, dp(12))
        })
        val password = TextInputEditText(context).apply { inputType = 0x81; maxLines = 1; hint = "Пароль VDS" }
        panel.addView(TextInputLayout(context).apply {
            hint = "Пароль VDS"
            setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
            addView(password)
        })
        val remember = com.google.android.material.checkbox.MaterialCheckBox(context).apply {
            text = "Сохранить на этом телефоне"
            isChecked = true
            setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_primary, context.theme))
        }
        panel.addView(remember)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage("${tunnel.adminUser}@${tunnel.adminHost}:${tunnel.adminPort ?: 22}")
            .setView(panel)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = password.text?.toString().orEmpty()
                        if (value.isBlank()) password.error = "Введите пароль VDS"
                        else {
                            dialog.dismiss()
                            onPassword(value, remember.isChecked)
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun isAuthenticationError(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            val text = it.message.orEmpty().lowercase()
            "auth fail" in text || "authentication" in text || "userauth" in text
        }
    private fun currentTunnel(): Tunnel? = vm.tunnels.value.map { it.tunnel }.firstOrNull { it.id == tunnelId }

    private fun knownHostsPath() = java.io.File(requireContext().filesDir, "ssh_known_hosts").apply { if (!exists()) createNewFile() }.absolutePath

    private fun readableError(error: Throwable): String {
        val text = generateSequence(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        return when {
            "UnknownHostKey" in text -> "SSH-ключ сервера не совпал с сохранённым. Проверьте адрес VDS и его ключ перед повторным подключением."
            "Auth fail" in text || "authentication" in text.lowercase() -> "Не удалось войти по SSH. Проверьте логин и пароль."
            "Control API 401" in text -> "Сервис управления отклонил доступ. Проверьте установку TERZAET на VDS."
            "Control API 400" in text -> "Сервер не принял данные пользователя. Проверьте имя и лимиты."
            "Control API 500" in text -> "Сервис не смог сохранить пользователя. Проверьте свободное место и права каталога TERZAET на VDS."
            "безопасное восстановление" in text -> "Не удалось безопасно восстановить сервис управления. Проверьте Docker и контейнер TERZAET на VDS; данные пользователей не удалялись."
            "не запустился после восстановления" in text -> "Сервис управления перезапущен, но не ответил. Проверьте состояние VDS и повторите попытку."
            "Connection refused" in text || "ConnectException" in text -> "Сервис управления на VDS не отвечает. Обновите или переустановите TERZAET через раздел «Админ»."
            "timed out" in text.lowercase() || "timeout" in text.lowercase() -> "Сервер не ответил. Проверьте доступность VDS и SSH-порт."
            else -> {
                val cause = generateSequence(error) { it.cause }.firstOrNull { !it.message.isNullOrBlank() }
                val detail = cause?.message?.take(180)?.replace(Regex("[\\r\\n]+"), " ")
                if (detail.isNullOrBlank()) "Не удалось связаться с сервером управления (${error.javaClass.simpleName}). Проверьте SSH-доступ и установку TERZAET."
                else "Не удалось связаться с сервером управления: $detail"
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun formatDate(value: String): String = runCatching {
        java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }.getOrDefault("неизвестно")

    override fun onNewEvent(ev: io.github.p1neapplexpress.openflux.event.AppEvent) = Unit
    companion object { fun new(tunnel: Tunnel) = UserManagementFragment().apply { arguments = Bundle().apply { putLong("tunnel", tunnel.id) } } }
}
