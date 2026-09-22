package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent

class ServerDetailsFragment : BaseFragment() {
    companion object {
        private const val TUNNEL = "tunnel"
        fun new(tunnel: Tunnel) = ServerDetailsFragment().apply { arguments = Bundle().apply { putLong(TUNNEL, tunnel.id) } }
    }

    private val vm: TunnelsViewModel by activityViewModels()
    private val tunnel get() = vm.tunnels.value.firstOrNull { it.tunnel.id == requireArguments().getLong(TUNNEL) }?.tunnel
        ?: error("Сервер не найден")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) = inflater.inflate(R.layout.fragment_server_details, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<TextView>(R.id.detailName).text = tunnel.name
        view.findViewById<TextView>(R.id.detailAddress).text = "${tunnel.adminHost} · SSH ${tunnel.adminPort ?: 22}"
        action(view, R.id.detailProtocols, R.drawable.ic_admin_protocols, "Установленные протоколы", "Состав и состояние на VDS") { showProtocols() }
        action(view, R.id.detailUsers, R.drawable.ic_admin_users, "Пользователи и ключи", "Доступ, лимиты и QR-коды") { open(UserManagementFragment.new(tunnel)) }
        action(view, R.id.detailConnection, R.drawable.ic_admin_edit, "Подключение VDS", "Адрес, порт и пользователь") {
            editConnection()
        }
        val content = view.findViewById<LinearLayout>(R.id.detailContent)
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            child.alpha = 0f
            child.translationY = 12f * resources.displayMetrics.density
            child.animate().alpha(1f).translationY(0f).setStartDelay(index * 35L).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        }
        UiAppearance.apply(view)
    }

    private fun action(view: View, id: Int, icon: Int, title: String, subtitle: String, click: () -> Unit) {
        val row = view.findViewById<View>(id)
        row.findViewById<ImageView>(R.id.adminActionIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.adminActionTitle).text = title
        row.findViewById<TextView>(R.id.adminActionSubtitle).text = subtitle
        row.setOnClickListener { click() }
    }

    private fun showProtocols() {
        open(InstalledProtocolsFragment.new(tunnel))
    }

    private fun editConnection() {
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 0, 56, 0) }
        val name = EditText(requireContext()).apply { hint = "Название сервера"; setText(tunnel.name) }
        val host = EditText(requireContext()).apply { hint = "IP или домен"; setText(tunnel.adminHost) }
        val user = EditText(requireContext()).apply { hint = "Пользователь"; setText(tunnel.adminUser ?: "root") }
        val port = EditText(requireContext()).apply { hint = "Порт SSH"; inputType = 2; setText((tunnel.adminPort ?: 22).toString()) }
        box.addView(name); box.addView(host); box.addView(user); box.addView(port)
        MaterialAlertDialogBuilder(requireContext()).setTitle("Подключение к VDS").setMessage("Пароль не сохраняется: при действиях на VDS приложение спросит его снова.")
            .setView(box).setNegativeButton(R.string.cancel, null).setPositiveButton("Сохранить") { _, _ ->
                val updated = tunnel.copy(name = name.text.toString().trim(), adminHost = host.text.toString().trim(), adminUser = user.text.toString().trim(), adminPort = port.text.toString().toIntOrNull() ?: 22)
                if (updated.name.isBlank() || updated.adminHost.isNullOrBlank() || updated.adminUser.isNullOrBlank() || updated.adminPort == null || updated.adminPort !in 1..65535) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle("Проверьте данные").setMessage("Укажите название, адрес, пользователя и корректный SSH-порт.").setPositiveButton("Понятно", null).show()
                } else vm.updateTunnel(tunnel, updated)
            }.show()
    }

    private fun open(fragment: BaseFragment) = requireActivity().supportFragmentManager.beginTransaction().replace(R.id.main, fragment).addToBackStack("server_detail").commit()

    override fun onNewEvent(ev: AppEvent) = Unit
}
