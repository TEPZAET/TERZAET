package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.ServerRelease
import io.github.p1neapplexpress.openflux.event.AppEvent
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ServersFragment : BaseFragment() {
    private val vm: TunnelsViewModel by activityViewModels()
    private lateinit var list: LinearLayout
    private var navigationPending = false

    override fun onResume() {
        super.onResume()
        navigationPending = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_servers, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        list = view.findViewById(R.id.serverList)
        view.findViewById<View>(R.id.serverAdd).setOnClickListener { open(ServerInstallFragment.new()) }
        UiAppearance.apply(view)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.tunnels, vm.selected) { items, _ -> items }.collect { items ->
                    render(items.map { it.tunnel }.filter { !it.adminHost.isNullOrBlank() })
                }
            }
        }
    }

    private fun render(items: List<Tunnel>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            val empty = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setPadding(dp(24), dp(42), dp(24), dp(42)); setBackgroundResource(R.drawable.glass_panel) }
            empty.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_admin_server); imageTintList = ColorStateList.valueOf(resources.getColor(R.color.colorPrimary, context.theme)); setPadding(dp(14), dp(14), dp(14), dp(14)); background = requireContext().getDrawable(R.drawable.admin_icon_bg) }, LinearLayout.LayoutParams(dp(64), dp(64)))
            empty.addView(TextView(requireContext()).apply { text = "Серверы отсутствуют"; textSize = 23f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER; setTextColor(resources.getColor(R.color.text_primary, context.theme)); setPadding(0, dp(19), 0, 0) })
            list.addView(empty)
            return
        }
        items.forEach { tunnel ->
            val row = layoutInflater.inflate(R.layout.item_server_compact, list, false)
            row.findViewById<TextView>(R.id.serverName).text = tunnel.name
            row.findViewById<TextView>(R.id.serverAddress).text = tunnel.adminHost
            val updateAvailable = ServerRelease.updateAvailable(tunnel)
            row.findViewById<TextView>(R.id.serverState).text = if (updateAvailable) "Обновление" else if (tunnel.id == vm.selectedTunnelId) "Выбран" else ""
            if (tunnel.id == vm.selectedTunnelId) row.setBackgroundResource(R.drawable.bg_server_selected)
            row.setOnClickListener { open(ServerDetailsFragment.new(tunnel)) }
            list.addView(row)
        }
    }

    private fun open(fragment: BaseFragment) {
        if (!isAdded || navigationPending) return
        val manager = requireActivity().supportFragmentManager
        if (manager.isStateSaved) return
        navigationPending = true
        manager.beginTransaction().replace(R.id.main, fragment).addToBackStack("server_edit").commit()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onNewEvent(ev: AppEvent) = Unit
}
