package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.EncryptionKey
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelPayload
import io.github.p1neapplexpress.openflux.event.AppEvent
import kotlinx.serialization.json.Json
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
        editing?.let { tunnel ->
            name.text = tunnel.name
            url.text = TunnelPayload.parse(tunnel.transportType, tunnel.transportConnPayload).url
            key.text = tunnel.encryptionKey.orEmpty()
            encryptionSwitch.isChecked = !tunnel.encryptionKey.isNullOrBlank()
            save.text = "Сохранить изменения"
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
            )
            editing?.let { vm.updateTunnel(it, tunnel) } ?: vm.addTunnel(tunnel)
            Toast.makeText(requireContext(), R.string.config_saved, Toast.LENGTH_SHORT).show()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
