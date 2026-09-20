package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.EncryptionKey
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelPayload
import io.github.p1neapplexpress.openflux.event.AppEvent
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

class AddTunFragment : BaseFragment() {
    companion object {
        private const val ARG_EDIT_JSON = "edit_json"
        fun new() = AddTunFragment()
        fun edit(tunnel: Tunnel) = AddTunFragment().apply {
            arguments = Bundle().apply { putString(ARG_EDIT_JSON, Json.encodeToString(Tunnel.serializer(), tunnel)) }
        }
    }

    private val vm: TunnelsViewModel by activityViewModels()
    private var editing: Tunnel? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        editing = arguments?.getString(ARG_EDIT_JSON)?.let {
            runCatching { Json.decodeFromString(Tunnel.serializer(), it) }.getOrNull()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_add_tun, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        val name = view.findViewById<TextView>(R.id.name)
        val url = view.findViewById<TextView>(R.id.documentUrl)
        val encryptionSwitch = view.findViewById<MaterialSwitch>(R.id.encryptionSwitch)
        val keyContainer = view.findViewById<TextInputLayout>(R.id.encryptionKeyContainer)
        val key = view.findViewById<TextView>(R.id.encryptionKey)
        val save = view.findViewById<Button>(R.id.saveButton)
        val removeFromVds = view.findViewById<Button>(R.id.removeFromVdsButton)
        editing?.let { tunnel ->
            name.text = tunnel.name
            url.text = TunnelPayload.parse(tunnel.transportType, tunnel.transportConnPayload).url
            key.text = tunnel.encryptionKey.orEmpty()
            encryptionSwitch.isChecked = !tunnel.encryptionKey.isNullOrBlank()
            save.text = "Сохранить изменения"
            removeFromVds.isVisible = !tunnel.adminHost.isNullOrBlank() && !tunnel.adminUser.isNullOrBlank()
        }
        keyContainer.isVisible = encryptionSwitch.isChecked
        encryptionSwitch.setOnCheckedChangeListener { _, enabled ->
            keyContainer.isVisible = enabled
            if (!enabled) key.text = ""
        }
        save.setOnClickListener {
            val tunnelName = name.text.toString().trim()
            val documentUrl = url.text.toString().trim()
            val rawKey = key.text.toString().trim()
            if (tunnelName.isBlank() || !documentUrl.startsWith("https://")) {
                Toast.makeText(requireContext(), "Введите имя и корректный URL документа", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (encryptionSwitch.isChecked && !EncryptionKey.isValid(rawKey)) {
                keyContainer.error = "Минимум 16 символов"
                return@setOnClickListener
            }
            val transport = editing?.let { TransportType.from(it.transportType) }
                ?.takeIf { it == TransportType.yandex || it == TransportType.vyandex }
                ?: TransportType.yandex
            val payload = TunnelPayload.build(TunnelPayload.Form(transport = transport, url = documentUrl))
                ?: return@setOnClickListener
            val tunnel = Tunnel(
                id = editing?.id ?: Random(System.currentTimeMillis()).nextLong(),
                name = tunnelName,
                transportType = transport.name,
                transportConnPayload = payload,
                encryptionKey = rawKey.takeIf { encryptionSwitch.isChecked }?.let(EncryptionKey::normalize),
                adminHost = editing?.adminHost,
                adminUser = editing?.adminUser,
                adminPort = editing?.adminPort,
            )
            editing?.let { vm.updateTunnel(it, tunnel) } ?: vm.addTunnel(tunnel)
            Toast.makeText(requireContext(), R.string.config_saved, Toast.LENGTH_SHORT).show()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        removeFromVds.setOnClickListener { editing?.let(::requestVdsRemoval) }
    }

    private fun requestVdsRemoval(tunnel: Tunnel) {
        val content = layoutInflater.inflate(R.layout.dialog_ssh_password, null)
        content.findViewById<TextView>(R.id.passwordHint).text = "Введите SSH-пароль для ${tunnel.adminUser}@${tunnel.adminHost}. Пароль используется один раз и не сохраняется."
        val password = content.findViewById<EditText>(R.id.sshPassword)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Удалить с VDS?")
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.action_delete, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = password.text.toString()
                if (value.isBlank()) {
                    password.error = "Введите пароль"
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { removeFromVds(tunnel, value) }
                    password.text.clear()
                    result.onSuccess {
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "TERZAET удалён с VDS", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        password.error = "Не удалось подключиться или удалить сервер"
                    }
                }
            }
        }
        dialog.show()
    }

    private fun removeFromVds(tunnel: Tunnel, password: String): Result<Unit> = runCatching {
        val host = tunnel.adminHost ?: error("VDS address missing")
        val user = tunnel.adminUser ?: error("VDS user missing")
        val jsch = JSch()
        val knownHosts = File(requireContext().filesDir, "ssh_known_hosts")
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.absolutePath)
        val session = jsch.getSession(user, host, tunnel.adminPort ?: 22)
        session.setPassword(password)
        session.userInfo = PasswordInfo(password)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.connect(15_000)
        val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        channel.setCommand("docker rm -f terzaet-yandex >/dev/null 2>&1 || true; rm -rf /opt/terzaet; printf TERZAET_REMOVED")
        val output = channel.inputStream
        channel.connect(15_000)
        val response = output.bufferedReader().readText()
        while (!channel.isClosed) Thread.sleep(100L)
        val code = channel.exitStatus
        channel.disconnect()
        session.disconnect()
        if (code != 0 || !response.contains("TERZAET_REMOVED")) error("Removal failed")
    }

    private class PasswordInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassword() = password
        override fun promptYesNo(message: String) = !message.contains("changed", ignoreCase = true)
        override fun getPassphrase(): String? = null
        override fun promptPassphrase(message: String?) = false
        override fun promptPassword(message: String?) = true
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?) = Array(prompt?.size ?: 0) { password }
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
