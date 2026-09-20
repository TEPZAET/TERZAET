package io.github.p1neapplexpress.openflux.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent

class SettingsFragment : BaseFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        val prefs = requireContext().getSharedPreferences("ui_settings", 0)
        bind(view.findViewById(R.id.settingVideo), prefs, "video", true)
        bind(view.findViewById(R.id.settingHaptics), prefs, "haptics", true)
        bind(view.findViewById(R.id.settingMotion), prefs, "motion", true)
        bind(view.findViewById(R.id.settingEnergy), prefs, "energy", false)
        bindSeek(view.findViewById(R.id.videoIntensity), prefs, "video_intensity", 100, 20)
        bindSeek(view.findViewById(R.id.glassTransparency), prefs, "glass_alpha", 72, 30)
        bindSeek(view.findViewById(R.id.textScale), prefs, "text_scale", 100, 85)
        UiAppearance.apply(view)
    }

    private fun bind(toggle: MaterialSwitch, prefs: SharedPreferences, key: String, fallback: Boolean) {
        toggle.isChecked = prefs.getBoolean(key, fallback)
        toggle.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    private fun bindSeek(seek: SeekBar, prefs: SharedPreferences, key: String, fallback: Int, offset: Int) {
        seek.progress = prefs.getInt(key, fallback) - offset
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) prefs.edit().putInt(key, progress + offset).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                UiAppearance.apply(requireView())
            }
        })
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
