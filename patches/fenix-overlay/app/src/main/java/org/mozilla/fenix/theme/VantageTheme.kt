/* Vantage: desktop-inspired dark theme (charcoal surfaces, blue actions, teal accents). */
package org.mozilla.fenix.theme

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import mozilla.components.compose.base.theme.AcornGradient
import mozilla.components.compose.base.theme.AcornGradientScheme
import mozilla.components.compose.base.theme.AcornGradientType
import mozilla.components.compose.base.theme.darkAcornGradientScheme
import mozilla.components.compose.base.theme.lightAcornGradientScheme
import mozilla.components.compose.base.utils.ColorStop

private const val VANTAGE_THEME_PREFS = "vantage_theme"
private const val VANTAGE_THEME_SELECTED = "vantage_theme_selected"
private const val VANTAGE_LIGHT_THEME_SELECTED = "vantage_light_theme_selected"

/** Vantage: whether the branded theme was explicitly picked in the theme list. */
fun isVantageThemeSelected(context: Context): Boolean =
    context.getSharedPreferences(VANTAGE_THEME_PREFS, Context.MODE_PRIVATE)
        .getBoolean(VANTAGE_THEME_SELECTED, false)

/** Vantage: remember/forget that the branded theme is the active choice. */
fun setVantageThemeSelected(context: Context, selected: Boolean) {
    context.getSharedPreferences(VANTAGE_THEME_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(VANTAGE_THEME_SELECTED, selected)
        .apply()
}

/** Vantage: whether the brand's white theme was explicitly picked in the theme list. */
fun isVantageLightThemeSelected(context: Context): Boolean =
    context.getSharedPreferences(VANTAGE_THEME_PREFS, Context.MODE_PRIVATE)
        .getBoolean(VANTAGE_LIGHT_THEME_SELECTED, false)

/** Vantage: remember/forget that the brand's white theme is the active choice. */
fun setVantageLightThemeSelected(context: Context, selected: Boolean) {
    context.getSharedPreferences(VANTAGE_THEME_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(VANTAGE_LIGHT_THEME_SELECTED, selected)
        .apply()
}

/**
 * Vantage: whether the branded dark palette should be used.
 *
 * This is true when the branded theme was explicitly selected, or when the user follows the
 * device theme and the device is currently in dark mode.
 */
fun usesVantageDarkPalette(context: Context): Boolean {
    val mode = AppCompatDelegate.getDefaultNightMode()
    return isVantageThemeSelected(context) ||
        mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ||
        mode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
}

/**
 * Vantage: whether the branded white palette should be used.
 *
 * The branded white theme is the default whenever the user follows the device theme, so a
 * fresh install on a light device starts on the Vantage white theme instead of Fenix's stock
 * light colours. Picking "Light" explicitly keeps the stock light palette.
 */
fun usesVantageLightPalette(context: Context): Boolean {
    val mode = AppCompatDelegate.getDefaultNightMode()
    return isVantageLightThemeSelected(context) ||
        mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ||
        mode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
}

/** Vantage: whether the branded chrome/palette applies to the current theme. */
fun usesVantageChrome(context: Context): Boolean {
    val isNightMode =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    return if (isNightMode) {
        usesVantageDarkPalette(context)
    } else {
        usesVantageLightPalette(context)
    }
}

private val vantageAccentGradient =
    AcornGradient(
        type = AcornGradientType.Linear(angleInDegrees = 96f),
        // Desktop-style blue -> teal loading/accent sweep.
        colorStops =
            listOf(
                ColorStop(0f, Color(0xFF4A8FF7)),
                ColorStop(0.71f, Color(0xFF22C7A6)),
            ),
    )

private val vantageAccentSubtleGradient =
    AcornGradient(
        type = AcornGradientType.Linear(angleInDegrees = 90f),
        // Desktop Vantage titlebar/tab strip: deep blue on the left, deep teal on the right.
        colorStops =
            listOf(
                ColorStop(0f, Color(0xFF16406E)),
                ColorStop(1f, Color(0xFF166B47)),
            ),
    )

/** Vantage: light-mode tab strip gradient (same blue -> green identity, brighter tints). */
private val vantageLightAccentSubtleGradient =
    AcornGradient(
        type = AcornGradientType.Linear(angleInDegrees = 90f),
        colorStops =
            listOf(
                ColorStop(0f, Color(0xFF79A9F7)),
                ColorStop(1f, Color(0xFF55CDB4)),
            ),
    )

/** Vantage: Acorn gradients used by the tab strip and the loading progress bar. */
internal val vantageAcornGradientScheme =
    AcornGradientScheme(
        cfr = darkAcornGradientScheme.cfr,
        accent = vantageAccentGradient,
        accentSubtle = vantageAccentSubtleGradient,
        tabOutline = vantageAccentGradient,
        privacyMask = darkAcornGradientScheme.privacyMask,
    )

/** Vantage: gradient set used by the light theme. */
internal val vantageLightAcornGradientScheme =
    AcornGradientScheme(
        cfr = lightAcornGradientScheme.cfr,
        accent = vantageAccentGradient,
        accentSubtle = vantageLightAccentSubtleGradient,
        tabOutline = vantageAccentGradient,
        privacyMask = lightAcornGradientScheme.privacyMask,
    )

internal fun vantageDarkColorScheme(): ColorScheme =
    darkColorScheme(
        primary = Color(0xFF4A8FF7),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF1D3C8F),
        onPrimaryContainer = Color(0xFFDCE7FF),
        secondary = Color(0xFF22C7A6),
        onSecondary = Color(0xFF04221C),
        secondaryContainer = Color(0xFF11463C),
        onSecondaryContainer = Color(0xFFC9F7EC),
        // Acorn's FilledButton uses `tertiary` as its container colour; keep it the
        // desktop-style action blue, with teal reserved for accents (secondary).
        tertiary = Color(0xFF4A8FF7),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF1D3C8F),
        onTertiaryContainer = Color(0xFFDCE7FF),
        background = Color(0xFF1C1B22),
        onBackground = Color(0xFFF5F4F8),
        // Desktop-style grey chrome for toolbars and search surfaces.
        surface = Color(0xFF23222B),
        onSurface = Color(0xFFF5F4F8),
        surfaceVariant = Color(0xFF2B2A33),
        onSurfaceVariant = Color(0xFFC8C6D0),
        surfaceContainerLowest = Color(0xFF151419),
        surfaceContainerLow = Color(0xFF1C1B22),
        surfaceContainer = Color(0xFF23222B),
        surfaceContainerHigh = Color(0xFF2B2A33),
        surfaceContainerHighest = Color(0xFF35343D),
        surfaceBright = Color(0xFF2B2A33),
        surfaceDim = Color(0xFF151419),
        outline = Color(0xFF4A4954),
        outlineVariant = Color(0xFF35343D),
        error = Color(0xFFFF6B6B),
        onError = Color(0xFF2A0000),
        // Vantage: Snackbar action buttons (e.g. the tab-close undo) read
        // `SnackbarDefaults.actionColor`, which resolves to `inversePrimary`.
        // Keep them in the brand blue instead of Acorn's purple.
        inversePrimary = Color(0xFF4A8FF7),
    )

/** Vantage: light counterpart of the branded palette, used by the light theme. */
internal fun vantageLightColorScheme(): ColorScheme =
    lightColorScheme(
        primary = Color(0xFF3B82F6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE7FF),
        onPrimaryContainer = Color(0xFF122C5C),
        secondary = Color(0xFF14B8A6),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFC9F7EC),
        onSecondaryContainer = Color(0xFF04302A),
        tertiary = Color(0xFF3B82F6),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDCE7FF),
        onTertiaryContainer = Color(0xFF122C5C),
        background = Color(0xFFFAF9FC),
        onBackground = Color(0xFF1C1B22),
        surface = Color(0xFFF4F3F7),
        onSurface = Color(0xFF1C1B22),
        surfaceVariant = Color(0xFFE9E7EF),
        onSurfaceVariant = Color(0xFF4A4954),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F6FA),
        surfaceContainer = Color(0xFFEFEDF3),
        surfaceContainerHigh = Color(0xFFE9E7EF),
        surfaceContainerHighest = Color(0xFFE1DFE8),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDEDCE4),
        outline = Color(0xFF74727E),
        outlineVariant = Color(0xFFC9C7D2),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF3B82F6),
    )
