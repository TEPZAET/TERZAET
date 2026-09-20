package io.github.p1neapplexpress.openflux.ui.rv

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.data.TunnelViewType
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus

class TunAdapter : RecyclerView.Adapter<TunAdapter.VH>() {

    var activeTunnel: TunnelState? = null
        set(value) {
            val old = field
            field = value
            items.forEachIndexed { i, item ->
                if (item.tunnel == old?.tunnel || item.tunnel == value?.tunnel) {
                    notifyItemChanged(i)
                }
            }
        }

    var items: List<TunnelViewType> = emptyList()
        set(value) {
            val diff = DiffUtil.calculateDiff(Diff(field, value))
            field = value
            diff.dispatchUpdatesTo(this)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transport_connection, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.item_conn_name)
        private val state: TextView = v.findViewById(R.id.item_conn_state)
        private val toggle: MaterialSwitch = v.findViewById(R.id.item_conn_enabled)
        private val dot: View = v.findViewById(R.id.item_status_dot)
        private var colorAnim: ValueAnimator? = null
        private var pulse: ValueAnimator? = null

        fun bind(item: TunnelViewType) {
            val isActive = activeTunnel?.tunnel == item.tunnel
            name.text = item.tunnel.name
            name.alpha = if (isActive) 1f else 0.6f

            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = isActive
            toggle.setOnCheckedChangeListener { v, checked ->
                EventBus.dispatch(AppEvent.ToggleTunnel(item.tunnel.id, checked))
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
            }

            if (isActive) {
                val color = activeTunnel?.color ?: 0xFFFFFFFF.toInt()
                val text = when (val s = activeTunnel) {
                    is TunnelState.Error -> s.message
                    is TunnelState.StartingTransport -> toggle.context.getString(R.string.starting_transport)
                    is TunnelState.StartingTun2Socks -> toggle.context.getString(R.string.starting_tsocks)
                    is TunnelState.Connecting -> toggle.context.getString(R.string.connecting)
                    is TunnelState.Running -> toggle.context.getString(R.string.running)
                    else -> ""
                }
                crossFade(state, text)
                animateColor(state, color)
                animateDot(activeTunnel)
            } else {
                state.text = when (TransportType.from(item.tunnel.transportType)) {
                    TransportType.yandex -> v.context.getString(R.string.yandex_docs_backend)
                    TransportType.vyandex -> v.context.getString(R.string.vyandex_backend)
                    TransportType.max -> v.context.getString(R.string.max_messenger_backend)
                    TransportType.cupsonline -> v.context.getString(R.string.cupsonline_backend)
                    TransportType.mailru -> v.context.getString(R.string.mailru_backend)
                }
                stopPulse()
                dot.isSelected = false
            }
        }

        private fun crossFade(tv: TextView, newText: String) {
            if (tv.text == newText) return
            val out = AlphaAnimation(1f, 0f).apply { duration = 150 }
            val into = AlphaAnimation(0f, 1f).apply { duration = 150 }
            out.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(a: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
                override fun onAnimationEnd(a: android.view.animation.Animation?) {
                    tv.text = newText; tv.startAnimation(into)
                }
            })
            tv.startAnimation(out)
        }

        private fun animateColor(tv: TextView, to: Int) {
            colorAnim?.cancel()
            colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), tv.currentTextColor, to).apply {
                duration = 300
                addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
                start()
            }
        }

        private fun animateDot(s: TunnelState?) {
            when (s) {
                is TunnelState.Running -> { startPulse(); dot.isSelected = true }
                is TunnelState.Error -> { stopPulse(); dot.isSelected = false }
                else -> { stopPulse(); dot.isSelected = true }
            }
        }

        private fun startPulse() {
            if (pulse?.isRunning == true) return
            pulse = ValueAnimator.ofFloat(0.8f, 1.2f).apply {
                duration = 1000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { dot.scaleX = it.animatedValue as Float; dot.scaleY = it.animatedValue as Float }
                start()
            }
        }

        private fun stopPulse() {
            pulse?.cancel(); dot.scaleX = 1f; dot.scaleY = 1f
        }

        fun recycle() { stopPulse(); colorAnim?.cancel() }
    }

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    private class Diff(private val old: List<TunnelViewType>, private val new: List<TunnelViewType>) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].tunnel.id == new[n].tunnel.id
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }
}
