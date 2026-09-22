package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.p1neapplexpress.openflux.R

class AccessKeyFragment : BaseFragment() {
    companion object {
        fun new(alias: String, key: String) = AccessKeyFragment().apply {
            arguments = Bundle().apply { putString("alias", alias); putString("key", key) }
        }
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?) =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(36), dp(22), dp(28))
            setBackgroundResource(R.drawable.bg_screen_ambient)
            addView(TextView(context).apply {
                text = "Ключ доступа"
                textSize = 26f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_primary, context.theme))
            })
            addView(TextView(context).apply {
                text = requireArguments().getString("alias").orEmpty()
                textSize = 15f
                setTextColor(resources.getColor(R.color.text_secondary, context.theme))
                setPadding(0, dp(5), 0, dp(20))
            })
            val key = requireArguments().getString("key").orEmpty()
            val qr = runCatching {
                val matrix = QRCodeWriter().encode(key, BarcodeFormat.QR_CODE, 480, 480)
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
                }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            }
            if (qr.isSuccess) {
                addView(ImageView(context).apply {
                    setImageBitmap(qr.getOrThrow())
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.WHITE) }
                    contentDescription = "QR-код ключа TERZAET"
                }, LinearLayout.LayoutParams(dp(292), dp(292)))
            } else {
                addView(TextView(context).apply {
                    text = "Ключ не поместился в QR-код. Скопируйте его кнопкой ниже."
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(resources.getColor(R.color.text_secondary, context.theme))
                    setPadding(dp(20), dp(30), dp(20), dp(30))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.WHITE) }
                }, LinearLayout.LayoutParams(-1, dp(180)))
            }
            addView(TextView(context).apply {
                text = "QR-код содержит полный ключ. Не показывайте его посторонним."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(resources.getColor(R.color.text_secondary, context.theme))
                setPadding(dp(8), dp(16), dp(8), dp(10))
            })
            addView(MaterialButton(context).apply {
                text = "Скопировать ключ"
                isAllCaps = false
                setIconResource(R.drawable.ic_key)
                setOnClickListener {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TERZAET key", key))
                    text = "Ключ скопирован"
                }
            }, LinearLayout.LayoutParams(-1, dp(54)))
        }

    override fun onViewCreated(view: android.view.View, state: Bundle?) { super.onViewCreated(view, state); UiAppearance.apply(view) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onNewEvent(ev: io.github.p1neapplexpress.openflux.event.AppEvent) = Unit
}
