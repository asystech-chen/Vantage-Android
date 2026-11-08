/* Vantage: install and manage Firefox desktop themes converted for the mobile UI. */
package org.mozilla.fenix.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.mozilla.fenix.R
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.theme.VantageTheme
import org.mozilla.fenix.theme.VantageThemeSearchResult
import org.mozilla.fenix.theme.VantageThemeStore

/** Lets the user search for, install, enable and delete desktop Firefox themes. */
class VantageThemesFragment : PreferenceFragmentCompat(), SystemInsetsPaddedFragment {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.vantage_themes_preferences, rootKey)

        requirePreference<Preference>(R.string.pref_key_vantage_themes_search)
            .setOnPreferenceClickListener {
                showSearchDialog()
                true
            }

        refreshInstalledThemes()
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.vantage_themes_title))
        refreshInstalledThemes()
    }

    private fun showSearchDialog() {
        val view =
            layoutInflater.inflate(R.layout.vantage_theme_search_dialog, null)
        val input = view.findViewById<TextInputEditText>(R.id.vantage_theme_search_input)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.vantage_themes_search_title)
            .setView(view)
            .setNegativeButton(R.string.vantage_themes_cancel, null)
            .setPositiveButton(R.string.vantage_themes_search_action) { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    searchThemes(query)
                }
            }
            .show()
    }

    private fun searchThemes(query: String) {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val results = try {
                VantageThemeStore.get(context).searchThemes(query)
            } catch (throwable: Exception) {
                Toast.makeText(context, R.string.vantage_themes_search_error, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }

            if (results.isEmpty()) {
                Toast.makeText(context, R.string.vantage_themes_search_empty, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }

            val labels = results.map { result ->
                getString(
                    R.string.vantage_themes_search_result,
                    result.name,
                    result.author.ifBlank { getString(R.string.vantage_themes_unknown_author) },
                )
            }.toTypedArray()

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vantage_themes_search_results_title)
                .setItems(labels) { _, index ->
                    installTheme(results[index])
                }
                .setNegativeButton(R.string.vantage_themes_cancel, null)
                .show()
        }
    }

    private fun installTheme(result: VantageThemeSearchResult) {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val message = try {
                val store = VantageThemeStore.get(context)
                val xpi = store.downloadThemeXpi(result.downloadUrl)
                store.installFromXpi(
                    bytes = xpi,
                    displayName = result.name,
                    authorOverride = result.author,
                )
                R.string.vantage_themes_install_success
            } catch (throwable: Exception) {
                R.string.vantage_themes_install_error
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            refreshInstalledThemes()
        }
    }

    private fun refreshInstalledThemes() {
        val store = VantageThemeStore.get(requireContext())
        val themes = store.installedThemes
        val activeId = store.activeThemeId

        requirePreference<Preference>(R.string.pref_key_vantage_themes_empty).isVisible =
            themes.isEmpty()

        val category =
            requirePreference<PreferenceCategory>(R.string.pref_key_vantage_installed_themes_category)
        category.removeAll()
        themes.forEach { theme ->
            category.addPreference(buildThemePreference(theme, isActive = theme.id == activeId))
        }
    }

    private fun buildThemePreference(theme: VantageTheme, isActive: Boolean): Preference =
        Preference(requireContext()).apply {
            key = INSTALLED_THEME_KEY_PREFIX + theme.id
            title = theme.name
            summary = buildString {
                append(
                    if (theme.author.isNotBlank()) {
                        getString(R.string.vantage_themes_author, theme.author)
                    } else {
                        getString(R.string.vantage_themes_unknown_author)
                    },
                )
                if (isActive) {
                    append(" · ")
                    append(getString(R.string.vantage_themes_active_delete_hint))
                }
            }
            isPersistent = false
            setOnPreferenceClickListener {
                // Tapping an enabled theme offers to delete it; any other theme becomes active.
                if (isActive) {
                    confirmDelete(theme)
                } else {
                    enableTheme(theme)
                }
                true
            }
        }

    private fun enableTheme(theme: VantageTheme) {
        val context = requireContext()
        VantageThemeStore.get(context).setActiveTheme(theme.id)
        org.mozilla.fenix.theme.setVantageThemeSelected(context, false)
        org.mozilla.fenix.theme.setVantageLightThemeSelected(context, false)

        val mode =
            if (theme.defaultIsDark) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
        with(requireComponents.core) {
            engine.settings.preferredColorScheme = getPreferredColorScheme()
        }
        requireComponents.useCases.sessionUseCases.reload.invoke()
        activity?.recreate()
    }

    private fun confirmDelete(theme: VantageTheme) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.vantage_themes_delete_title)
            .setMessage(getString(R.string.vantage_themes_delete_message, theme.name))
            .setNegativeButton(R.string.vantage_themes_cancel, null)
            .setPositiveButton(R.string.vantage_themes_delete) { _, _ ->
                val store = VantageThemeStore.get(requireContext())
                val wasActive = store.activeThemeId == theme.id
                store.deleteTheme(theme.id)
                if (wasActive) {
                    // Fall back to the default device-theme behaviour once the custom theme is gone.
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    activity?.recreate()
                } else {
                    refreshInstalledThemes()
                }
            }
            .show()
    }

    companion object {
        /** Prefix for the dynamically added installed-theme preferences. */
        const val INSTALLED_THEME_KEY_PREFIX = "vantage_installed_theme_"
    }
}
