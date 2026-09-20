package io.github.p1neapplexpress.openflux.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.min
class PulseRingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private data class Ring(val startedAt: Long, val duration: Long)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * resources.displayMetrics.density
    }

    private val rings = mutableListOf<Ring>()
    private var ringColor: Int = Color.parseColor("#22C55E")
    private var emissionIntervalMs = 1500L
    private var lastEmitAt = 0L
    private var active = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            val now = System.currentTimeMillis()
            if (now - lastEmitAt >= emissionIntervalMs) {
                rings.add(Ring(now, 2400L))
                lastEmitAt = now
            }
            rings.removeAll { now - it.startedAt > it.duration }
            invalidate()
            postOnAnimationDelayed(this, 16L)
        }
    }

    fun start(color: Int, intervalMs: Long = 1500L) {
        ringColor = color
        emissionIntervalMs = intervalMs
        if (active) return
        active = true
        lastEmitAt = 0L
        rings.clear()
        postOnAnimation(ticker)
    }

    fun stop() {
        active = false
        removeCallbacks(ticker)
        rings.clear()
        invalidate()
    }

    fun setColor(color: Int) {
        ringColor = color
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rings.isEmpty()) return

        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f - paint.strokeWidth
        val minR = maxR * 0.52f

        val now = System.currentTimeMillis()
        for (ring in rings) {
            val t = ((now - ring.startedAt).toFloat() / ring.duration).coerceIn(0f, 1f)
            val easeOut = 1f - (1f - t) * (1f - t) * (1f - t)
            val r = minR + (maxR - minR) * easeOut
            val alpha = ((1f - t) * 150f).toInt().coerceIn(0, 255)
            paint.color = ColorUtils.setAlphaComponent(ringColor, alpha)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}
