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
    private var tunnelId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); tunnelId = requireArguments().getLong("tunnel") }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?): View {
        val root = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(22); setBackgroundResource(io.github.p1neapplexpress.openflux.R.drawable.bg_screen_ambient) }
        root.addView(TextView(requireContext()).apply { text = "Пользователи и ключи"; textSize = 27f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_primary, context.theme)) })
        status = TextView(requireContext()).apply { text = "Пароль VDS запрашивается только для текущего действия"; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_secondary, context.theme)); setPadding(0, 12, 0, 12) }
        root.addView(status)
        val add = MaterialButton(requireContext()).apply { text = "Добавить пользователя"; isAllCaps = false; setOnClickListener { askPasswordAndCreate() } }
        root.addView(add)
        list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 18, 0, 0) }
        root.addView(list)
        return root
    }

    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); loadUsers() }

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
                        ControlApiClient(tunnel.adminHost!!, tunnel.adminUser!!, tunnel.adminPort ?: 22, password).use { api ->
                            api.createUser(ManagedUserRequest(alias.text.toString().trim(), (traffic.text.toString().toLongOrNull() ?: 0) * 1_073_741_824L, (time.text.toString().toLongOrNull() ?: 0) * 3600, listOfNotNull(tunnel.hysteriaUri?.let { ManagedProfile("Hy2", it) })))
                        }
                    }.onSuccess { user -> requireActivity().runOnUiThread { showBundle(user.bundle); loadUsers() } }.onFailure { requireActivity().runOnUiThread { status.text = "Ошибка API: ${it.message}" } }
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
                runCatching { ControlApiClient(tunnel.adminHost!!, tunnel.adminUser!!, tunnel.adminPort ?: 22, password.text.toString()).use { it.listUsers() } }
                    .onSuccess { users -> requireActivity().runOnUiThread { render(users) } }.onFailure { requireActivity().runOnUiThread { status.text = "Не удалось подключиться к control API: ${it.message}" } }
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
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(18); setBackgroundResource(io.github.p1neapplexpress.openflux.R.drawable.glass_panel) }
            row.addView(TextView(requireContext()).apply { text = "${user.alias}\n${if (user.revokedAt == null) "Доступ активен" else "Доступ отозван"}"; textSize = 15f; setTextColor(resources.getColor(io.github.p1neapplexpress.openflux.R.color.text_primary, context.theme)) })
            val copy = MaterialButton(requireContext()).apply { text = "Скопировать ключ"; isAllCaps = false; setOnClickListener { showBundle(user.bundle) } }
            row.addView(copy)
            if (user.revokedAt == null) row.addView(MaterialButton(requireContext()).apply { text = "Отозвать доступ"; isAllCaps = false; setOnClickListener { askPasswordForAction(user.id, false) } })
            row.addView(MaterialButton(requireContext()).apply { text = "Удалить"; isAllCaps = false; setOnClickListener { askPasswordForAction(user.id, true) } })
            list.addView(row)
        }
    }

    private fun askPasswordForAction(id: String, delete: Boolean) {
        val tunnel = currentTunnel() ?: return
        val password = EditText(requireContext()).apply { inputType = 0x81; hint = "Пароль VDS" }
        MaterialAlertDialogBuilder(requireContext()).setTitle(if (delete) "Удалить пользователя?" else "Отозвать доступ?").setView(password)
            .setNegativeButton("Отмена", null).setPositiveButton("Подтвердить") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { ControlApiClient(tunnel.adminHost!!, tunnel.adminUser!!, tunnel.adminPort ?: 22, password.text.toString()).use { if (delete) it.deleteUser(id) else it.revokeUser(id) } }
                        .onSuccess { requireActivity().runOnUiThread { status.text = if (delete) "Пользователь удалён" else "Доступ отозван"; loadUsers() } }
                        .onFailure { requireActivity().runOnUiThread { status.text = "Ошибка API: ${it.message}" } }
                }
            }.show()
    }
    private fun currentTunnel(): Tunnel? = vm.tunnels.value.map { it.tunnel }.firstOrNull { it.id == tunnelId }

    override fun onNewEvent(ev: io.github.p1neapplexpress.openflux.event.AppEvent) = Unit
    companion object { fun new(tunnel: Tunnel) = UserManagementFragment().apply { arguments = Bundle().apply { putLong("tunnel", tunnel.id) } } }
}
