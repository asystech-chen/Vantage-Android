/* Vantage: preview with three tappable toolbar shortcut slots. */
package org.mozilla.fenix.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.R as materialR
import com.google.android.material.color.MaterialColors
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components

/**
 * Preference that renders a small toolbar preview whose first three icons are tappable.
 * Tapping an icon selects which shortcut slot is being edited; the option list is rendered
 * by the hosting fragment below this preference.
 */
internal class VantageToolbarSlotsPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.vantage_toolbar_slots_preference
        isSelectable = false
    }

    var icons: List<Int?> = listOf(null, null, null)
    var selectedIndex: Int = 0
    var onSlotClick: ((Int) -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val slots = listOf<View>(
            holder.itemView.findViewById(R.id.vantage_slot_1),
            holder.itemView.findViewById(R.id.vantage_slot_2),
            holder.itemView.findViewById(R.id.vantage_slot_3),
        )
        val images = listOf(
            holder.itemView.findViewById<ImageView>(R.id.vantage_slot_icon_1),
            holder.itemView.findViewById<ImageView>(R.id.vantage_slot_icon_2),
            holder.itemView.findViewById<ImageView>(R.id.vantage_slot_icon_3),
        )

        slots.forEachIndexed { index, slot ->
            val icon = icons.getOrNull(index)
            if (icon != null && icon != 0) {
                images[index].setImageResource(icon)
            } else {
                images[index].setImageDrawable(null)
            }

            slot.background =
                if (index == selectedIndex) {
                    GradientDrawable().apply {
                        cornerRadius = context.resources.displayMetrics.density * 10f
                        setColor(MaterialColors.getColor(slot, materialR.attr.colorSecondaryContainer))
                    }
                } else {
                    null
                }

            slot.contentDescription =
                context.getString(R.string.preferences_toolbar_select_shortcut) + " " + (index + 1)
            slot.setOnClickListener { onSlotClick?.invoke(index) }
        }

        // Vantage: the preview must mirror the real toolbar, including the live tab count.
        holder.itemView.findViewById<TextView>(R.id.vantage_slots_tab_counter)?.text =
            context.components.core.store.state.tabs.size.toString()
    }
}
