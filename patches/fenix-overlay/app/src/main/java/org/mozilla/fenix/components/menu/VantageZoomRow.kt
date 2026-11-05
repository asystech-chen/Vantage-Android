/* Vantage: page zoom + full screen controls shown inside the browser menu. */
package org.mozilla.fenix.components.menu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.engine.gecko.setPageZoomFactor
import mozilla.components.concept.engine.EngineSession
import mozilla.components.support.ktx.android.view.enterImmersiveMode
import mozilla.components.support.ktx.android.view.exitImmersiveMode

private object VantagePageZoomMemory {
    private val values = mutableMapOf<String, Float>()

    fun get(key: String): Float = values[key] ?: 1.0f

    fun set(key: String, value: Float) {
        values[key] = value
    }
}

private object VantageFullScreenState {
    var enabled: Boolean = false
}

private tailrec fun Context.vantageActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.vantageActivity()
        else -> null
    }

/**
 * Zoom row displayed inside the "more" menu: [-] percentage [+] and a full screen toggle.
 *
 * @param sessionId id of the tab the menu was opened for.
 * @param engineSession engine session of that tab, used to apply the zoom.
 * @param onFinish called when the menu should be dismissed.
 */
@Composable
internal fun VantageZoomRow(
    sessionId: String?,
    engineSession: EngineSession?,
    onFinish: () -> Unit = {},
) {
    val key = sessionId ?: "vantage-default"
    var zoom by remember(key) { mutableStateOf(VantagePageZoomMemory.get(key)) }
    var fullScreen by remember { mutableStateOf(VantageFullScreenState.enabled) }
    val context = LocalContext.current

    fun applyZoom(target: Float) {
        val value = (target * 10).roundToInt() / 10f
        val clamped = value.coerceIn(0.3f, 3.0f)
        zoom = clamped
        VantagePageZoomMemory.set(key, clamped)
        (engineSession as? GeckoEngineSession)?.setPageZoomFactor(clamped)
    }

    // Styled like the other "More" menu rows so the zoom controls read as part of the list.
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = 2.dp)
                .clip(shape = MaterialTheme.shapes.extraSmall)
                .background(color = MaterialTheme.colorScheme.surfaceBright)
                .defaultMinSize(minHeight = 52.dp)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { applyZoom(zoom - 0.1f) },
            modifier = Modifier.size(40.dp),
        ) {
            Text(text = "\u2212", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        }

        Text(
            text = "${(zoom * 100).roundToInt()}%",
            modifier = Modifier.widthIn(min = 44.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        IconButton(
            onClick = { applyZoom(zoom + 0.1f) },
            modifier = Modifier.size(40.dp),
        ) {
            Text(text = "+", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            onClick = {
                val activity = context.vantageActivity() ?: return@TextButton
                fullScreen = !fullScreen
                VantageFullScreenState.enabled = fullScreen
                // Close the menu first, otherwise dismissing the dialog restores the system bars.
                onFinish()
                activity.window.decorView.postDelayed(
                    {
                        if (VantageFullScreenState.enabled) {
                            activity.enterImmersiveMode()
                        } else {
                            activity.exitImmersiveMode()
                        }
                    },
                    250,
                )
            }
        ) {
            Text(text = if (fullScreen) "退出全屏" else "全屏")
        }
    }
}
