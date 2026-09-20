package io.github.p1neapplexpress.openflux.ui.widget

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min
class AuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val evaluator = ArgbEvaluator()

    private var currentColor: Int = Color.parseColor("#4F7CFF")
    private var targetColor: Int = currentColor
    private var colorAnim: ValueAnimator? = null

    private var intensity = 0.55f
    private var targetIntensity = 0.55f
    private var intensityAnim: ValueAnimator? = null

    private var breath = 0f
    private val breathAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            breath = it.animatedValue as Float
            invalidate()
        }
    }

    fun setStateColor(color: Int, animated: Boolean = true) {
        if (color == targetColor) return
        targetColor = color
        colorAnim?.cancel()
        if (!animated) {
            currentColor = color; invalidate(); return
        }
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                currentColor = evaluator.evaluate(
                    it.animatedFraction, currentColor, targetColor
                ) as Int
                invalidate()
            }
            start()
        }
    }

    fun setIntensity(value: Float, animated: Boolean = true) {
        val v = value.coerceIn(0f, 1f)
        if (v == targetIntensity) return
        targetIntensity = v
        intensityAnim?.cancel()
        if (!animated) {
            intensity = v; invalidate(); return
        }
        intensityAnim = ValueAnimator.ofFloat(intensity, v).apply {
            duration = 600L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                intensity = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!breathAnim.isStarted) breathAnim.start()
    }

    override fun onDetachedFromWindow() {
        breathAnim.cancel()
        colorAnim?.cancel()
        intensityAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseR = min(width, height) / 2f
        
        val radius = baseR * (0.92f + breath * 0.08f)

        
        val a0 = (55 + 90 * intensity).toInt().coerceIn(0, 160)
        val rgb = currentColor and 0x00FFFFFF

        val c0 = (rgb or (a0 shl 24))          
        val c1 = (rgb or ((a0 * 55 / 100) shl 24))   
        val c2 = (rgb or ((a0 * 22 / 100) shl 24))   
        val c3 = (rgb or ((a0 * 6 / 100) shl 24))    
        val c4 = (rgb or 0x00000000)           

        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(c0, c1, c2, c3, c4),
            floatArrayOf(0.00f, 0.28f, 0.52f, 0.76f, 1.00f),
            Shader.TileMode.CLAMP,
        )

        canvas.drawCircle(cx, cy, radius, paint)
    }
}
