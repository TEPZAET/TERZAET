package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.ConnectionMode
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.ServerRelease
import io.github.p1neapplexpress.openflux.event.AppEvent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class ServersFragment : BaseFragment() {
    private val vm: TunnelsViewModel by activityViewModels()
    private lateinit var list: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_servers, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        list = view.findViewById(R.id.serverList)
        view.findViewById<View>(R.id.serverAdd).setOnClickListener { open(AddTunFragment.new()) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.tunnels.collect { items -> render(items.map { it.tunnel }) }
            }
        }
    }

    private fun render(items: List<Tunnel>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(requireContext()).apply {
                text = getString(R.string.no_configs)
                setTextColor(resources.getColor(R.color.text_secondary, context.theme))
                textSize = 14f
                setPadding(12, 24, 12, 24)
            })
            return
        }
        items.forEach { tunnel ->
            val row = layoutInflater.inflate(R.layout.item_server_manage, list, false)
            row.findViewById<TextView>(R.id.serverName).text = tunnel.name
            val updateAvailable = ServerRelease.updateAvailable(tunnel)
            row.findViewById<View>(R.id.serverDot).backgroundTintList = if (updateAvailable) {
                ColorStateList.valueOf(android.graphics.Color.parseColor("#E5B642"))
            } else null
            row.findViewById<TextView>(R.id.serverType).text = if (updateAvailable) {
                "Доступно обновление сервера"
            } else TransportType.from(tunnel.transportType).name
            val selectedMode = ConnectionMode.from(tunnel.connectionMode)
            val yandex = row.findViewById<MaterialButton>(R.id.serverYandex)
            val hysteria = row.findViewById<MaterialButton>(R.id.serverHysteria)
            setModeStyle(yandex, selectedMode == ConnectionMode.yandex || (selectedMode == ConnectionMode.auto && tunnel.hysteriaUri.isNullOrBlank()), "ЯDoc")
            setModeStyle(hysteria, selectedMode != ConnectionMode.yandex && !tunnel.hysteriaUri.isNullOrBlank(), "Hy2")
            hysteria.alpha = if (tunnel.hysteriaUri.isNullOrBlank()) 0.45f else 1f
            yandex.setOnClickListener { vm.setConnectionMode(tunnel, ConnectionMode.yandex) }
            hysteria.setOnClickListener {
                if (tunnel.hysteriaUri.isNullOrBlank()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Hysteria 2 ещё не настроена")
                        .setMessage("Откройте изменение сервера и добавьте ссылку Hysteria 2. Яндекс Документ продолжит работать как основной транспорт.")
                        .setPositiveButton("Понятно", null)
                        .show()
                } else {
                    vm.setConnectionMode(tunnel, ConnectionMode.hysteria2)
                }
            }
            row.setOnClickListener { vm.selectTunnel(tunnel) }
            val active = vm.active.value.isActive && vm.active.value.tunnel?.id == tunnel.id
            row.findViewById<ImageButton>(R.id.serverEdit).apply {
                alpha = if (active) 0.3f else 1f
                setOnClickListener { if (!active) open(AddTunFragment.edit(tunnel)) }
            }
            row.findViewById<ImageButton>(R.id.serverDelete).apply {
                alpha = if (active) 0.3f else 1f
                setOnClickListener { if (!active) confirmDelete(tunnel) }
            }
            list.addView(row)
        }
    }

    private fun setModeStyle(button: MaterialButton, selected: Boolean, label: String) {
        button.text = if (selected) "✓  $label" else label
        button.alpha = 1f
        button.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor(if (selected) "#3F454A" else "#CCFFFFFF"))
        button.setTextColor(android.graphics.Color.parseColor(if (selected) "#FFFFFF" else "#3F454A"))
    }

    private fun confirmDelete(tunnel: Tunnel) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_config_title)
            .setMessage(tunnel.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> vm.removeTunnel(tunnel) }
            .show()
    }

    private fun open(fragment: BaseFragment) {
        requireActivity().supportFragmentManager.beginTransaction().replace(R.id.main, fragment).addToBackStack("server_edit").commit()
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
