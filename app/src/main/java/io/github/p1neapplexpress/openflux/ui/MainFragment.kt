package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent

class MainFragment : BaseFragment() {

    private lateinit var pager: ViewPager2
    private lateinit var navItems: List<View>

    override fun onNewEvent(ev: AppEvent) = Unit

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pager = view.findViewById(R.id.view_pager)
        pager.adapter = PagerAdapter(this)
        pager.offscreenPageLimit = 3
        pager.isUserInputEnabled = false
        navItems = listOf(
            view.findViewById(R.id.globalNavHome),
            view.findViewById(R.id.globalNavServers),
            view.findViewById(R.id.globalNavLogs),
            view.findViewById(R.id.globalNavAbout),
        )
        navItems.forEachIndexed { index, item ->
            item.setOnClickListener { pager.setCurrentItem(index, true) }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderSelected(position)
        })
        renderSelected(0)
    }

    private fun renderSelected(position: Int) {
        navItems.forEachIndexed { index, view ->
            if (index == position) view.setBackgroundResource(R.drawable.nav_active_glass)
            else view.setBackgroundResource(0)
            view.animate().alpha(if (index == position) 1f else 0.68f).setDuration(180L).start()
        }
    }

    private inner class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 4
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> TunnelsFragment()
            1 -> ServersFragment()
            2 -> LogsFragment()
            3 -> AboutFragment()
            else -> error("bad position $position")
        }
    }
}
