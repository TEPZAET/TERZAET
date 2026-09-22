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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.ConnectionMode
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import com.jcraft.jsch.JSch

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
        action(view, R.id.detailProtocols, R.drawable.ic_admin_protocols, "Протоколы", "ЯDoc и Hysteria 2") { showProtocols() }
        action(view, R.id.detailUsers, R.drawable.ic_admin_users, "Пользователи и ключи", "Доступ, лимиты и QR-коды") { open(UserManagementFragment.new(tunnel)) }
        action(view, R.id.detailConnection, R.drawable.ic_admin_edit, "Подключение VDS", "Адрес, порт и пользователь") {
            editConnection()
        }
        view.findViewById<View>(R.id.detailDanger).setOnClickListener { confirmRemove() }
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
        val yandex = tunnel.transportConnPayload.isNotEmpty()
        val hy2 = !tunnel.hysteriaUri.isNullOrBlank()
        val text = buildString {
            append("Яндекс Документы: ").append(if (yandex) "установлен" else "не установлен")
            append("\nHysteria 2: ").append(if (hy2) "установлена" else "не установлена")
            append("\n\nАктивный выбор: ").append(if (ConnectionMode.from(tunnel.connectionMode) == ConnectionMode.hysteria2) "Hy2" else "ЯDoc")
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Протоколы").setMessage(text)
            .setNegativeButton("ЯDoc") { _, _ -> if (yandex) vm.setConnectionMode(tunnel, ConnectionMode.yandex) }
            .setNeutralButton("Hy2") { _, _ -> if (hy2) vm.setConnectionMode(tunnel, ConnectionMode.hysteria2) }
            .setPositiveButton("Готово", null).show()
    }

    private fun confirmRemove() {
        val password = EditText(requireContext()).apply { hint = "Пароль VDS"; inputType = 0x81 }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Удалить TERZAET с VDS?")
            .setMessage("Будут удалены только TERZAET, его контейнеры и служба управления. Amnezia и другие VPN не затрагиваются.")
            .setView(password).setNegativeButton(R.string.cancel, null).setPositiveButton("Удалить") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { removeFromVds(password.text.toString()) }.onSuccess {
                        requireActivity().runOnUiThread { vm.removeTunnel(tunnel); requireActivity().supportFragmentManager.popBackStack() }
                    }.onFailure { error ->
                        requireActivity().runOnUiThread { MaterialAlertDialogBuilder(requireContext()).setTitle("Удаление не выполнено").setMessage(error.message).setPositiveButton("Понятно", null).show() }
                    }
                }
            }.show()
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

    private fun removeFromVds(password: String) {
        require(password.isNotBlank()) { "Введите пароль VDS" }
        val known = File(requireContext().filesDir, "ssh_known_hosts").apply { if (!exists()) createNewFile() }
        val session = JSch().apply { setKnownHosts(known.absolutePath) }.getSession(tunnel.adminUser ?: "root", tunnel.adminHost, tunnel.adminPort ?: 22)
        session.setPassword(password); session.setConfig("StrictHostKeyChecking", "ask"); session.connect(15_000)
        val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        channel.setCommand("docker rm -f terzaet-yandex terzaet-hysteria terzaet-control >/dev/null 2>&1 || true; systemctl disable --now terzaet-hysteria.service >/dev/null 2>&1 || true; rm -rf /opt/terzaet /opt/terzaet-hysteria")
        channel.connect(15_000); while (!channel.isClosed) Thread.sleep(50)
        val code = channel.exitStatus; channel.disconnect(); session.disconnect(); if (code != 0) error("VDS вернул код $code")
    }

    private fun open(fragment: BaseFragment) = requireActivity().supportFragmentManager.beginTransaction().replace(R.id.main, fragment).addToBackStack("server_detail").commit()

    override fun onNewEvent(ev: AppEvent) = Unit
}
