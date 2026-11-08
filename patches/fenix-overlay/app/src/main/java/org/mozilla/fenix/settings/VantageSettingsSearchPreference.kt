/* Vantage: inline "search settings" field shown at the top of the settings screen. */
package org.mozilla.fenix.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as materialR
import org.mozilla.fenix.R

/**
 * Vantage: a non-selectable preference that renders a search field. Text changes are forwarded
 * to [onQueryChanged] so the settings list can be filtered live.
 */
class VantageSettingsSearchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

    var onQueryChanged: ((String) -> Unit)? = null

    private var query: String = ""

    init {
        layoutResource = R.layout.vantage_settings_search
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val container = holder.findViewById(R.id.vantage_settings_search_container)
        container.background =
            GradientDrawable().apply {
                cornerRadius = container.resources.displayMetrics.density * 10f
                setColor(
                    MaterialColors.getColor(
                        container,
                        materialR.attr.colorSurfaceContainerHigh,
                    ),
                )
            }

        val input = holder.findViewById(R.id.vantage_settings_search_input) as? EditText ?: return

        // The holder can be reused, so only attach the text watcher once per view.
        if (input.getTag(R.id.vantage_settings_search_input) != true) {
            input.setTag(R.id.vantage_settings_search_input, true)
            input.doAfterTextChanged { editable ->
                val text = editable?.toString().orEmpty()
                if (text != query) {
                    query = text
                    onQueryChanged?.invoke(text)
                }
            }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(input.windowToken, 0)
                    true
                } else {
                    false
                }
            }
        }

        if (input.text?.toString().orEmpty() != query) {
            input.setText(query)
            input.setSelection(query.length)
        }
    }
}
