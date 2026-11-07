/* Vantage: device-width based UI scaling (desktop-like proportions on every phone). */
package org.mozilla.fenix.theme

import android.content.Context
import android.content.res.Configuration

private const val VANTAGE_REFERENCE_WIDTH_DP = 440f
private const val VANTAGE_MIN_SCALE = 0.85f

/**
 * Scales the whole activity (density + font scale) so the UI keeps desktop-like proportions
 * instead of looking oversized on phones with unusually large logical widths / densities.
 *
 * Devices narrower than [VANTAGE_REFERENCE_WIDTH_DP] get proportionally smaller UI, while
 * tablets and wide screens keep their natural size (the scale is never larger than 1).
 */
fun scaleVantageContext(base: Context): Context {
    val configuration = Configuration(base.resources.configuration)
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    val densityDpi = configuration.densityDpi
    // Use the device's smallest width so the scale stays identical after rotation.
    val referenceWidthDp =
        configuration.smallestScreenWidthDp.takeIf { it > 0 } ?: widthDp

    if (widthDp <= 0 || heightDp <= 0 || densityDpi <= 0 || referenceWidthDp <= 0) {
        return base
    }

    val scale = (referenceWidthDp.toFloat() / VANTAGE_REFERENCE_WIDTH_DP)
        .coerceIn(VANTAGE_MIN_SCALE, 1.0f)
    if (scale >= 0.999f) {
        return base
    }

    val oldDensity = densityDpi.toFloat() / 160f
    val widthPx = widthDp * oldDensity
    val heightPx = heightDp * oldDensity

    configuration.densityDpi = (densityDpi * scale).toInt()
    val newDensity = configuration.densityDpi.toFloat() / 160f
    configuration.screenWidthDp = (widthPx / newDensity).toInt()
    configuration.screenHeightDp = (heightPx / newDensity).toInt()
    configuration.smallestScreenWidthDp =
        minOf(configuration.screenWidthDp, configuration.screenHeightDp)

    return base.createConfigurationContext(configuration)
}
