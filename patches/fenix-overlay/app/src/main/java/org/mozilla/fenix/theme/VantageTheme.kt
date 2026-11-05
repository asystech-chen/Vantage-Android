/* Vantage: blue/teal dark theme colour scheme. */
package org.mozilla.fenix.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

internal fun vantageDarkColorScheme(): ColorScheme =
    darkColorScheme(
        primary = Color(0xFF2F6BFF),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF1D3C8F),
        onPrimaryContainer = Color(0xFFDCE7FF),
        secondary = Color(0xFF22D3EE),
        onSecondary = Color(0xFF04222B),
        secondaryContainer = Color(0xFF0F4657),
        onSecondaryContainer = Color(0xFFD6F8FF),
        tertiary = Color(0xFF14B8A6),
        onTertiary = Color(0xFF00201C),
        tertiaryContainer = Color(0xFF0B5A50),
        onTertiaryContainer = Color(0xFFB8F5EC),
        background = Color(0xFF0B1220),
        onBackground = Color(0xFFE8F0FA),
        surface = Color(0xFF111A2B),
        onSurface = Color(0xFFE8F0FA),
        surfaceVariant = Color(0xFF1B2740),
        onSurfaceVariant = Color(0xFFB9C7DB),
        surfaceContainerLowest = Color(0xFF070D18),
        surfaceContainerLow = Color(0xFF0D1524),
        surfaceContainer = Color(0xFF131E32),
        surfaceContainerHigh = Color(0xFF1A2740),
        surfaceContainerHighest = Color(0xFF22314E),
        // Cards / menu rows read these, so keep them inside the blue palette instead of
        // falling back to Material's neutral greys.
        surfaceBright = Color(0xFF1C2A45),
        surfaceDim = Color(0xFF0A111C),
        outline = Color(0xFF3C4F6B),
        outlineVariant = Color(0xFF27354C),
        error = Color(0xFFFF6B6B),
        onError = Color(0xFF2A0000),
    )
