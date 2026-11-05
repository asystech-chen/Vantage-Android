/* Vantage: extra editable toolbar shortcut slots (2nd and 3rd buttons). */
package org.mozilla.fenix.settings

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * A toolbar shortcut preference backed by a plain SharedPreferences key, used for the extra
 * (second and third) editable slots in the expanded toolbar.
 *
 * @param context Android context.
 * @param prefKey SharedPreferences key storing the shortcut value.
 * @param defaultKey Value used when nothing has been chosen yet.
 */
internal class VantageSlotShortcutPreference(
    context: Context,
    private val prefKey: String,
    private val defaultKey: String,
) : ToolbarExpandedShortcutPreference(context) {

    /** Optional callback for when a new shortcut option is selected. */
    var optionChangedListener: ((ShortcutOption?) -> Unit)? = null

    override fun readSelectedKey(): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(prefKey, defaultKey) ?: defaultKey

    /** Icon of the currently selected shortcut for this slot, if any. */
    fun selectedIcon(): Int? {
        val type = ShortcutType.fromValue(readSelectedKey())
        return options.firstOrNull { it.key == type }?.icon ?: options.firstOrNull()?.icon
    }

    override fun writeSelectedKey(key: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(prefKey, key).apply()
        optionChangedListener?.invoke(options.firstOrNull { it.key.value == key })
    }
}
