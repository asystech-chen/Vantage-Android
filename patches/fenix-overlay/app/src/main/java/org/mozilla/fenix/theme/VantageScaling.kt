/* Vantage: proportional UI scaling for phones with unusual display/font sizes. */
package org.mozilla.fenix.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private const val REFERENCE_SCREEN_WIDTH_DP = 440f

/**
 * Scales the UI relative to the device width (and clamps the system font scale) so
 * very large display/font settings - e.g. some Huawei ROMs - do not blow up the layout.
 */
@Composable
fun VantageScaledContent(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val widthScale =
        (configuration.screenWidthDp / REFERENCE_SCREEN_WIDTH_DP).coerceIn(0.85f, 1.0f)

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density * widthScale,
            fontScale = (density.fontScale * widthScale).coerceIn(0.85f, 1.25f),
        ),
        content = content,
    )
}
