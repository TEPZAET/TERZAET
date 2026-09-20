package io.github.p1neapplexpress.openflux.ui

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

object UiAppearance {
    fun apply(root: View) {
        val textScale = 1f
        val glassAlpha = (72 * 2.55f).toInt()
        walk(root, textScale, glassAlpha)
        root.alpha = 0f
        root.translationY = 12f * root.resources.displayMetrics.density
        root.animate().alpha(1f).translationY(0f).setDuration(280L).start()
    }

    private fun walk(view: View, textScale: Float, glassAlpha: Int) {
        if (view is TextView) {
            val base = view.getTag(io.github.p1neapplexpress.openflux.R.id.base_text_size) as? Float ?: view.textSize / view.resources.displayMetrics.scaledDensity
            view.setTag(io.github.p1neapplexpress.openflux.R.id.base_text_size, base)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * textScale)
        }
        if (view.tag == "glass") view.background?.mutate()?.alpha = glassAlpha
        if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index), textScale, glassAlpha)
    }
}
