package io.github.p1neapplexpress.openflux.ui

import androidx.fragment.app.Fragment
import io.github.p1neapplexpress.openflux.event.AppEvent

abstract class BaseFragment : Fragment() {
    abstract fun onNewEvent(ev: AppEvent)
}
