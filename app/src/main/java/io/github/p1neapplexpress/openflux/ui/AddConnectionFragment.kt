package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TunnelBundleParser

class AddConnectionFragment : BaseFragment() {
    private val vm: TunnelsViewModel by activityViewModels()
    private lateinit var keyField: TextInputEditText
    private lateinit var keyInput: TextInputLayout

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?) =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(38), dp(22), dp(28))
            setBackgroundResource(R.drawable.bg_screen_ambient)
            addView(TextView(context).apply {
                text = "Добавить конфигурацию"
                textSize = 26f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_primary, context.theme))
            })
            addView(TextView(context).apply {
                text = "Вставьте пользовательский ключ TERZAET, полученный от администратора."
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_secondary, context.theme))
                setPadding(0, dp(8), 0, dp(18))
            })
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                setBackgroundResource(R.drawable.glass_panel)
            }
            keyField = TextInputEditText(context).apply {
                minLines = 3
                maxLines = 7
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE
            }
            keyInput = TextInputLayout(context).apply {
                hint = "Ключ TERZAET"
                setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
                addView(keyField)
            }
            card.addView(keyInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            card.addView(MaterialButton(context).apply {
                text = "Вставить из буфера"
                isAllCaps = false
                setOnClickListener {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                    if (clip == null || clip.itemCount == 0) keyInput.error = "Буфер обмена пуст"
                    else {
                        keyField.setText(clip.getItemAt(0).coerceToText(context))
                        keyInput.error = null
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
            card.addView(MaterialButton(context).apply {
                text = "Добавить конфигурацию"
                isAllCaps = false
                backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.colorPrimary, context.theme))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener { importKey() }
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
            addView(card)
        }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        UiAppearance.apply(view)
    }

    private fun importKey() {
        val value = keyField.text?.toString().orEmpty()
        runCatching { TunnelBundleParser.parse(value) }
            .onSuccess { tunnel ->
                vm.addTunnel(tunnel)
                Toast.makeText(requireContext(), "Подключение добавлено", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
            .onFailure { keyInput.error = "Ключ не распознан. Вставьте строку целиком." }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onNewEvent(ev: io.github.p1neapplexpress.openflux.event.AppEvent) = Unit
}
