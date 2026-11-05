/* Vantage: extra editable toolbar shortcut slots (2nd and 3rd buttons). */
package org.mozilla.fenix.settings

import android.content.Context
import androidx.preference.PreferenceManager
import org.mozilla.fenix.ext.components

/**
 * A toolbar shortcut preference for the editable slots in the expanded toolbar.
 *
 * The first slot reuses Fenix's own expanded shortcut setting (stored in `fenix_preferences`),
 * because that is what the browser and home toolbars actually read. The extra Vantage slots
 * (second and third) live in the default SharedPreferences file instead.
 *
 * @param context Android context.
 * @param prefKey SharedPreferences key storing the shortcut value (extra slots only).
 * @param defaultKey Value used when nothing has been chosen yet.
 * @param isPrimary Whether this is Fenix's primary expanded shortcut (slot 1).
 */
internal class VantageSlotShortcutPreference(
    context: Context,
    private val prefKey: String,
    private val defaultKey: String,
    private val isPrimary: Boolean = false,
) : ToolbarExpandedShortcutPreference(context) {

    /** Optional callback for when a new shortcut option is selected. */
    var optionChangedListener: ((ShortcutOption?) -> Unit)? = null

    override fun readSelectedKey(): String =
        if (isPrimary) {
            context.components.settings.toolbarExpandedShortcutKey
        } else {
            PreferenceManager.getDefaultSharedPreferences(context).getString(prefKey, defaultKey) ?: defaultKey
        }

    /** Icon of the currently selected shortcut for this slot, if any. */
    fun selectedIcon(): Int? {
        val type = ShortcutType.fromValue(readSelectedKey())
        return options.firstOrNull { it.key == type }?.icon ?: options.firstOrNull()?.icon
    }

    override fun writeSelectedKey(key: String) {
        if (isPrimary) {
            context.components.settings.toolbarExpandedShortcutKey = key
        } else {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(prefKey, key).apply()
        }
        optionChangedListener?.invoke(options.firstOrNull { it.key.value == key })
    }
}
