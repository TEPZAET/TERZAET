package io.github.p1neapplexpress.openflux.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent

class SettingsFragment : BaseFragment() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        view?.findViewById<MaterialSwitch>(R.id.settingNotifications)?.isChecked = granted
        requireContext().getSharedPreferences("ui_settings", 0).edit().putBoolean("recovery_notifications", granted).apply()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        val prefs = requireContext().getSharedPreferences("ui_settings", 0)
        prefs.edit().putBoolean("video", true).putBoolean("motion", true).putBoolean("haptics", true).putBoolean("energy", false).apply()
        val intensity = view.findViewById<SeekBar>(R.id.videoIntensity)
        intensity.progress = prefs.getInt("video_intensity", 100).coerceIn(20, 100) - 20
        intensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) prefs.edit().putInt("video_intensity", progress + 20).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        val notifications = view.findViewById<MaterialSwitch>(R.id.settingNotifications)
        notifications.isChecked = prefs.getBoolean("recovery_notifications", true) && hasNotificationPermission()
        notifications.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                prefs.edit().putBoolean("recovery_notifications", checked).apply()
            }
        }
        UiAppearance.apply(view)
    }

    private fun hasNotificationPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onNewEvent(ev: AppEvent) = Unit
}
