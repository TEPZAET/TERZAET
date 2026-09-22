package io.github.p1neapplexpress.openflux.ui

import android.Manifest
import android.net.Uri
import android.media.AudioManager
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.ui.widget.CropVideoView

class OnboardingFragment : BaseFragment() {
    private var page = 0
    private lateinit var card: View
    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var step: TextView
    private lateinit var button: MaterialButton
    private lateinit var dots: List<View>
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        page = 2
        render()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        page = 3
        render()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = state?.getInt("page")
            ?: requireContext().getSharedPreferences("ui_settings", 0).getInt("onboarding_page", 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_onboarding, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        card = view.findViewById(R.id.onboardingCard)
        title = view.findViewById(R.id.onboardingTitle)
        text = view.findViewById(R.id.onboardingText)
        step = view.findViewById(R.id.onboardingStep)
        button = view.findViewById(R.id.onboardingNext)
        dots = listOf(view.findViewById(R.id.dotOne), view.findViewById(R.id.dotTwo), view.findViewById(R.id.dotThree), view.findViewById(R.id.dotFour))
        val video = view.findViewById<CropVideoView>(R.id.onboardingVideo)
        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        video.setVideoURI(Uri.parse("android.resource://${requireContext().packageName}/${R.raw.woman_glass_hq}"))
        video.setOnPreparedListener { player ->
            player.isLooping = true
            player.setVolume(0f, 0f)
            video.setSourceSize(player.videoWidth, player.videoHeight)
            video.start()
        }
        button.setOnClickListener { next() }
        UiAppearance.apply(view)
        render(false)
    }

    private fun next() {
        when (page) {
            0 -> { page = 1; render() }
            1 -> {
                val intent = VpnService.prepare(requireContext())
                if (intent == null) { page = 2; render() } else vpnPermission.launch(intent)
            }
            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    page = 3
                    render()
                }
            }
            else -> finish()
        }
    }

    private fun render(animated: Boolean = true) {
        requireContext().getSharedPreferences("ui_settings", 0).edit().putInt("onboarding_page", page).apply()
        val titles = listOf("Добро пожаловать", "Разрешение VPN", "Уведомления", "Всё готово")
        val texts = listOf(
            "TERZAET объединяет конфигурации, состояние подключения и диагностику в одном спокойном интерфейсе.",
            "Android покажет системный запрос. Разрешение требуется только для создания защищённого VPN-подключения.",
            "Разрешите уведомления, чтобы видеть восстановление соединения и состояние VPN, когда приложение свёрнуто.",
            "Добавьте сервер вручную или установите TERZAET на свой VDS во вкладке «Админ»."
        )
        val buttons = listOf("Продолжить", "Разрешить VPN", "Разрешить уведомления", "Открыть TERZAET")
        val update = {
            title.text = titles[page]
            text.text = texts[page]
            button.text = buttons[page]
            step.text = "0${page + 1} / 04"
            dots.forEachIndexed { index, dot ->
                dot.layoutParams = dot.layoutParams.apply { width = if (index == page) 24.dp(dot) else 8.dp(dot) }
                dot.background.setTint(ContextCompat.getColor(requireContext(), if (index == page) R.color.text_primary else R.color.text_tertiary))
            }
        }
        if (!animated) { update(); return }
        card.animate().alpha(0f).translationY(14f).setDuration(150L).withEndAction {
            update()
            card.animate().alpha(1f).translationY(0f).setDuration(320L).start()
        }.start()
    }

    private fun Int.dp(view: View) = (this * view.resources.displayMetrics.density).toInt()

    private fun finish() {
        requireContext().getSharedPreferences("ui_settings", 0).edit()
            .putBoolean("onboarding_done", true)
            .remove("onboarding_page")
            .apply()
        parentFragmentManager.beginTransaction().replace(R.id.main, MainFragment()).commitAllowingStateLoss()
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
