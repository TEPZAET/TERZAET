package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.Tunnel

class InstalledProtocolsFragment : BaseFragment() {
    companion object {
        fun new(tunnel: Tunnel) = InstalledProtocolsFragment().apply { arguments = Bundle().apply { putLong("tunnel", tunnel.id) } }
    }

    private val vm: TunnelsViewModel by activityViewModels()
    private val tunnel get() = vm.tunnels.value.firstOrNull { it.tunnel.id == requireArguments().getLong("tunnel") }?.tunnel

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?) =
        LinearLayout(requireContext()).apply {
            val page = this
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(38), dp(22), dp(28))
            setBackgroundResource(R.drawable.bg_screen_ambient)
            addView(TextView(context).apply { text = "Установленные протоколы"; textSize = 25f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(R.color.text_primary, context.theme)) })
            addView(TextView(context).apply { text = tunnel?.name.orEmpty(); textSize = 14f; setTextColor(resources.getColor(R.color.text_secondary, context.theme)); setPadding(0, dp(6), 0, dp(18)) })
            val t = tunnel
            if (t == null) {
                addView(TextView(context).apply { text = "Сервер не найден"; gravity = Gravity.CENTER; setTextColor(resources.getColor(R.color.text_secondary, context.theme)) })
            } else {
                protocolCard(page, "Яндекс Документы", t.transportConnPayload.isNotEmpty(), "Транспорт TCP")
                protocolCard(page, "Hysteria 2", !t.hysteriaUri.isNullOrBlank(), "Транспорт UDP · подходит для звонков")
                if (t.hysteriaUri.isNullOrBlank()) addView(MaterialButton(context).apply {
                    text = "Установить Hysteria 2"
                    isAllCaps = false
                    setOnClickListener { open(AddTunFragment.edit(t)) }
                }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) })
                if (t.transportConnPayload.isEmpty()) addView(MaterialButton(context).apply {
                    text = "Установить Яндекс Документы"
                    isAllCaps = false
                    setOnClickListener { open(ServerInstallFragment.new()) }
                }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
                addView(TextView(context).apply {
                    text = "После установки протокол появится в ключах, созданных заново. Уже выданные ключи автоматически не обновляются."
                    textSize = 12f
                    setTextColor(resources.getColor(R.color.text_tertiary, context.theme))
                    setPadding(dp(4), dp(16), dp(4), 0)
                })
            }
        }

    private fun protocolCard(page: LinearLayout, name: String, installed: Boolean, subtitle: String) {
        val card = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(15), dp(17), dp(15)); setBackgroundResource(R.drawable.glass_panel) }
        card.addView(TextView(requireContext()).apply { text = name; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(R.color.text_primary, context.theme)) })
        card.addView(TextView(requireContext()).apply { text = "$subtitle · ${if (installed) "Установлен" else "Не установлен"}"; textSize = 12f; setTextColor(resources.getColor(if (installed) R.color.state_running else R.color.text_secondary, context.theme)); setPadding(0, dp(4), 0, 0) })
        page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun open(fragment: BaseFragment) { parentFragmentManager.beginTransaction().replace(R.id.main, fragment).addToBackStack("protocol_install").commit() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onViewCreated(view: android.view.View, state: Bundle?) { super.onViewCreated(view, state); UiAppearance.apply(view) }
    override fun onNewEvent(ev: io.github.p1neapplexpress.openflux.event.AppEvent) = Unit
}
