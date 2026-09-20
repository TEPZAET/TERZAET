package io.github.p1neapplexpress.openflux.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView
import kotlin.math.max
class CropVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VideoView(context, attrs, defStyleAttr) {
    private var sourceWidth = 0
    private var sourceHeight = 0

    fun setSourceSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val targetWidth = MeasureSpec.getSize(widthMeasureSpec)
        val targetHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val scale = max(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
        setMeasuredDimension((sourceWidth * scale).toInt(), (sourceHeight * scale).toInt())
    }
}
