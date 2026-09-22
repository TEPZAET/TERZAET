package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
import io.github.p1neapplexpress.openflux.data.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class UserManagementFragment : BaseFragment() {
    private val vm: TunnelsViewModel by activityViewModels()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
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
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; colors = intArrayOf(Color.rgb(69, 144, 109), Color.rgb(35, 103, 77)) }
        }
        hero.addView(emblem, LinearLayout.LayoutParams(dp(58), dp(58)))
        val intro = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        intro.addView(TextView(context).apply { text = "ДОСТУП К СЕРВЕРУ"; textSize = 10f; letterSpacing = .08f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme)) })
        intro.addView(TextView(context).apply { text = "Пользователи"; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_primary, context.theme)) })
        intro.addView(TextView(context).apply { text = currentTunnel()?.name.orEmpty(); textSize = 13f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme)) })
        hero.addView(intro, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(hero)
        content.addView(TextView(context).apply { text = "УПРАВЛЕНИЕ ДОСТУПОМ"; textSize = 11f; letterSpacing = .08f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_tertiary, context.theme)); setPadding(dp(4), dp(24), dp(4), dp(10)) })
        val statusCard = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(13), dp(16), dp(13)); setBackgroundResource(io.github.p1neapplexpress.openflux.R.drawable.bg_glass_field) }
        statusCard.addView(TextView(context).apply { text = "●"; textSize = 13f; setTextColor(Color.rgb(54, 153, 108)) })
        status = TextView(context).apply { text = "Пароль запрашивается для каждого действия"; textSize = 13f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme)); setPadding(dp(10), 0, 0, 0) }
        statusCard.addView(status, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(statusCard)
        val add = MaterialButton(context).apply {
            text = "Добавить пользователя"; isAllCaps = false; textSize = 15f
            setIconResource(io.github.p1neapplexpress.openflux.R.drawable.ic_user_add); iconPadding = dp(10)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(42, 125, 88))
            setOnClickListener { askPasswordAndCreate() }
        }
        content.addView(add, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(14) })
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(24), 0, 0) }; content.addView(list)
        return scroller
    }

    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); UiAppearance.apply(view); loadUsers() }

    private fun askPasswordAndCreate() {
        val password = EditText(requireContext()).apply { inputType = 0x81; hint = "Пароль VDS" }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Добавить пользователя").setView(password)
            .setMessage("Пароль не сохраняется и нужен только для защищённого SSH‑туннеля управления.")
            .setNegativeButton("Отмена", null).setPositiveButton("Продолжить") { _, _ -> showUserForm(password.text.toString()) }.show()
    }

    private fun showUserForm(password: String) {
        val alias = TextInputEditText(requireContext()).apply { hint = "Имя или алиас" }
        val traffic = TextInputEditText(requireContext()).apply { hint = "Лимит трафика, ГБ (0 — без лимита)"; inputType = 2 }
        val time = TextInputEditText(requireContext()).apply { hint = "Лимит времени, часов (0 — без лимита)"; inputType = 2 }
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24) }
        listOf(alias, traffic, time).forEach { box.addView(TextInputLayout(requireContext()).apply { addView(it) }) }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Новый пользователь").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Создать") { _, _ ->
                val tunnel = currentTunnel() ?: return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        ControlApiClient(tunnel.adminHost!!, tunnel.adminUser!!, tunnel.adminPort ?: 22, password, knownHostsPath()).use { api ->
                            api.createUser(ManagedUserRequest(alias.text.toString().trim(), (traffic.text.toString().toLongOrNull() ?: 0) * 1_073_741_824L, (time.text.toString().toLongOrNull() ?: 0) * 3600, listOfNotNull(tunnel.hysteriaUri?.let { ManagedProfile("Hy2", it) })))
                        }
                    }.onSuccess { user -> requireActivity().runOnUiThread { showBundle(user.bundle); loadUsers() } }.onFailure { requireActivity().runOnUiThread { status.text = readableError(it) } }
                }
            }.show()
    }

    private fun showBundle(bundle: String) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("TERZAET key", bundle))
        val matrix = QRCodeWriter().encode(bundle, BarcodeFormat.QR_CODE, 720, 720)
        val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        for (x in 0 until 720) for (y in 0 until 720) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val image = ImageView(requireContext()).apply { setImageBitmap(bitmap); setPadding(20) }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Ключ пользователя").setView(image).setMessage("QR и текстовый ключ скопированы в буфер обмена.").setPositiveButton("Готово", null).show()
        status.text = "Пользователь создан. Ключ скопирован в буфер обмена."
    }

    private fun loadUsers() {
        val tunnel = currentTunnel() ?: return
        if (tunnel.adminHost.isNullOrBlank() || tunnel.adminUser.isNullOrBlank()) { status.text = "Для этого сервера нет данных администратора"; return }
        askPasswordForLoad(tunnel)
    }

    private fun askPasswordForLoad(tunnel: Tunnel) {
        val password = EditText(requireContext()).apply { inputType = 0x81; hint = "Пароль VDS" }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Доступ к пользователям").setView(password).setNegativeButton("Позже", null).setPositiveButton("Открыть") { _, _ ->
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { ControlApiClient(tunnel.adminHost!!, tunnel.adminUser!!, tunnel.adminPort ?: 22, password.text.toString(), knownHostsPath()).use { it.listUsers() } }
                    .onSuccess { users -> requireActivity().runOnUiThread { render(users) } }.onFailure { requireActivity().runOnUiThread { status.text = readableError(it) } }
            }
        }.show()
    }

    private fun render(users: List<ManagedUser>) {
        list.removeAllViews()
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
                text = user.alias.take(1).uppercase(); textSize = 17f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.rgb(35, 112, 78))
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.rgb(222, 242, 231)) }
            }
            heading.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))
            val identity = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            identity.addView(TextView(requireContext()).apply { text = user.alias; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_primary, context.theme)) })
            identity.addView(TextView(requireContext()).apply { text = if (user.revokedAt == null) "●  Доступ активен" else "Доступ отозван"; textSize = 12f; setTextColor(if (user.revokedAt == null) Color.rgb(43, 137, 91) else Color.GRAY); setPadding(0, dp(3), 0, 0) })
            heading.addView(identity, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(heading)
            val copy = MaterialButton(requireContext()).apply { text = "Показать ключ и QR"; isAllCaps = false; setIconResource(io.github.p1neapplexpress.openflux.R.drawable.ic_key); iconPadding = dp(8); setOnClickListener { showBundle(user.bundle) } }
            row.addView(copy, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
            val actions = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            if (user.revokedAt == null) actions.addView(MaterialButton(requireContext()).apply {
                text = "Отозвать"; isAllCaps = false; textSize = 12f
                setOnClickListener { askPasswordForAction(user.id, false) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            actions.addView(MaterialButton(requireContext()).apply {
                text = "Удалить"; isAllCaps = false; textSize = 12f
                setTextColor(Color.rgb(151, 69, 75))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 239, 240))
                setOnClickListener { askPasswordForAction(user.id, true) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (user.revokedAt == null) marginStart = dp(8) })
            row.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            list.addView(row)
            row.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun askPasswordForAction(id: String, delete: Boolean) {
        val tunnel = currentTunnel() ?: return
        val password = EditText(requireContext()).apply { inputType = 0x81; hint = "Пароль VDS" }
        MaterialAlertDialogBuilder(requireContext()).setTitle(if (delete) "Удалить пользователя?" else "Отозвать доступ?").setView(password)
            .setNegativeButton("Отмена", null).setPositiveButton("Подтвердить") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { ControlApiClient(tunnel.adminHost!!, tunnel.adminUser!!, tunnel.adminPort ?: 22, password.text.toString(), knownHostsPath()).use { if (delete) it.deleteUser(id) else it.revokeUser(id) } }
                        .onSuccess { requireActivity().runOnUiThread { status.text = if (delete) "Пользователь удалён" else "Доступ отозван"; loadUsers() } }
                        .onFailure { requireActivity().runOnUiThread { status.text = readableError(it) } }
                }
            }.show()
    }
    private fun currentTunnel(): Tunnel? = vm.tunnels.value.map { it.tunnel }.firstOrNull { it.id == tunnelId }

    private fun knownHostsPath() = java.io.File(requireContext().filesDir, "ssh_known_hosts").apply { if (!exists()) createNewFile() }.absolutePath

    private fun readableError(error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            "UnknownHostKey" in text -> "SSH-ключ сервера не совпал с сохранённым. Проверьте адрес VDS и его ключ перед повторным подключением."
            "Auth fail" in text || "authentication" in text.lowercase() -> "Не удалось войти по SSH. Проверьте логин и пароль."
            "timed out" in text.lowercase() || "timeout" in text.lowercase() -> "Сервер не ответил. Проверьте доступность VDS и SSH-порт."
            else -> "Не удалось связаться с сервером управления. Проверьте пароль и установку TERZAET."
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onNewEvent(ev: io.github.p1neapplexpress.openflux.event.AppEvent) = Unit
    companion object { fun new(tunnel: Tunnel) = UserManagementFragment().apply { arguments = Bundle().apply { putLong("tunnel", tunnel.id) } } }
}
