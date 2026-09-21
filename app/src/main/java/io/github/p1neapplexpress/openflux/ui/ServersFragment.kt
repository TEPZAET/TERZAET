package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
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
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            row.findViewById<TextView>(R.id.serverType).text = TransportType.from(tunnel.transportType).name
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

    private fun confirmDelete(tunnel: Tunnel) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_config_title)
            .setMessage(tunnel.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> vm.removeTunnel(tunnel) }
            .show()
    }

    private fun open(fragment: BaseFragment) {
        parentFragmentManager.beginTransaction().replace(R.id.main, fragment).addToBackStack("server_edit").commit()
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
