/* Vantage: desktop-inspired dark theme (charcoal surfaces, blue actions, teal accents). */
package org.mozilla.fenix.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import mozilla.components.compose.base.theme.AcornGradient
import mozilla.components.compose.base.theme.AcornGradientScheme
import mozilla.components.compose.base.theme.AcornGradientType
import mozilla.components.compose.base.theme.darkAcornGradientScheme
import mozilla.components.compose.base.utils.ColorStop

private const val VANTAGE_THEME_PREFS = "vantage_theme"
private const val VANTAGE_THEME_SELECTED = "vantage_theme_selected"

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

/** Vantage: Acorn gradients used by the tab strip and the loading progress bar. */
internal val vantageAcornGradientScheme =
    AcornGradientScheme(
        cfr = darkAcornGradientScheme.cfr,
        accent = vantageAccentGradient,
        accentSubtle = vantageAccentSubtleGradient,
        tabOutline = vantageAccentGradient,
        privacyMask = darkAcornGradientScheme.privacyMask,
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
