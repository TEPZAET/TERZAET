package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
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
        view.findViewById<View>(R.id.serverAdd).setOnClickListener { open(ServerInstallFragment.new()) }
        UiAppearance.apply(view)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.tunnels.collect { items -> render(items.map { it.tunnel }.filter { !it.adminHost.isNullOrBlank() }) }
            }
        }
    }

    private fun render(items: List<Tunnel>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            val empty = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundResource(R.drawable.glass_panel) }
            empty.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_admin_server); imageTintList = ColorStateList.valueOf(resources.getColor(R.color.colorPrimary, context.theme)); setPadding(dp(14), dp(14), dp(14), dp(14)); background = requireContext().getDrawable(R.drawable.admin_icon_bg) }, LinearLayout.LayoutParams(dp(56), dp(56)))
            empty.addView(TextView(requireContext()).apply { text = "Здесь появятся ваши VDS"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER; setTextColor(resources.getColor(R.color.text_primary, context.theme)); setPadding(0, dp(14), 0, dp(4)) })
            empty.addView(TextView(requireContext()).apply { text = "Добавьте сервер, чтобы установить TERZAET и управлять подключениями."; textSize = 13f; gravity = android.view.Gravity.CENTER; setTextColor(resources.getColor(R.color.text_secondary, context.theme)) })
            list.addView(empty)
            return
        }
        items.forEach { tunnel ->
            val row = layoutInflater.inflate(R.layout.item_server_manage, list, false)
            row.findViewById<TextView>(R.id.serverName).text = tunnel.name
            row.findViewById<TextView>(R.id.serverAddress).text = "${tunnel.adminHost} · SSH ${tunnel.adminPort ?: 22}"
            val updateAvailable = ServerRelease.updateAvailable(tunnel)
            row.findViewById<View>(R.id.serverDot).backgroundTintList = if (updateAvailable) {
                ColorStateList.valueOf(android.graphics.Color.parseColor("#E5B642"))
            } else null
            row.findViewById<TextView>(R.id.serverType).text = if (updateAvailable) {
                "Доступно обновление сервера"
            } else TransportType.from(tunnel.transportType).name
            (row.findViewById<View>(R.id.serverYandex).parent as View).visibility = View.GONE
            row.setOnClickListener { open(ServerDetailsFragment.new(tunnel)) }
            row.findViewById<ImageButton>(R.id.serverEdit).apply {
                visibility = View.GONE
            }
            row.findViewById<ImageButton>(R.id.serverDelete).apply {
                visibility = View.GONE
            }
            row.findViewById<MaterialButton>(R.id.serverUsers).visibility = View.GONE
            list.addView(row)
        }
    }

    private fun open(fragment: BaseFragment) {
        requireActivity().supportFragmentManager.beginTransaction().replace(R.id.main, fragment).addToBackStack("server_edit").commit()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onNewEvent(ev: AppEvent) = Unit
}
