/* Vantage: desktop theme backgrounds for the homepage and the header chrome. */
package org.mozilla.fenix.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.fenix.theme.VantageThemeStore

/** Decodes the active desktop theme's header image off the main thread. */
@Composable
fun rememberVantageThemeImage(isDark: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val store = remember(context) { VantageThemeStore.get(context) }
    val activeThemeId = store.activeThemeId

    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = activeThemeId,
        key2 = isDark,
    ) {
        value = withContext(Dispatchers.IO) {
            store.loadActiveBackgroundBitmap(isDark)?.asImageBitmap()
        }
    }
    return bitmap
}

/**
 * Vantage: paints the homepage with the dominant colour extracted from the theme image instead of
 * using the image itself as a full-screen wallpaper.
 */
@Composable
fun VantageThemeBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { VantageThemeStore.get(context) }
    val theme = store.activeTheme ?: return
    val isDark = isSystemInDarkTheme()
    val palette = remember(theme.id, isDark) { store.paletteFor(theme, isDark) }

    Box(modifier = modifier.fillMaxSize().background(palette.homeBackground))
}

/**
 * Vantage: draws the theme image behind the tab strip and the search bar so both share one
 * continuous header background, mirroring the desktop browser chrome.
 */
@Composable
fun VantageThemeHeaderBackground(modifier: Modifier = Modifier) {
    val bitmap = rememberVantageThemeImage(isDark = isSystemInDarkTheme()) ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}
