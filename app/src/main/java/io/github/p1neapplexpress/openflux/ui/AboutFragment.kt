package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import io.github.p1neapplexpress.openflux.BuildConfig
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent

class AboutFragment : BaseFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_about, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<TextView>(R.id.versionText).text = "Версия ${BuildConfig.VERSION_NAME}"
        val logo = view.findViewById<ImageView>(R.id.aboutLogo)
        val title = view.findViewById<TextView>(R.id.aboutTitle)
        logo.alpha = 0f
        logo.scaleX = 0.9f
        logo.scaleY = 0.9f
        title.alpha = 0f
        title.translationY = 12f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420L).start()
        title.animate().alpha(1f).translationY(0f).setStartDelay(100L).setDuration(420L).start()
        UiAppearance.apply(view)
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
