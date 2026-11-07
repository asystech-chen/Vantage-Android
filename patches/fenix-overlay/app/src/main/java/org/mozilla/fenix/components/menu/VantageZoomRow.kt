/* Vantage: page zoom + full screen controls shown inside the browser menu. */
package org.mozilla.fenix.components.menu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.engine.gecko.setPageZoomFactor
import mozilla.components.concept.engine.EngineSession
import androidx.core.view.WindowCompat
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.VantageScaledContent

private object VantagePageZoomMemory {
    private val values = mutableMapOf<String, Float>()

    fun get(key: String): Float = values[key] ?: 1.0f

    fun set(key: String, value: Float) {
        values[key] = value
    }
}

/**
 * Vantage: single source of truth for full screen. Reading the actual window insets
 * keeps the menu state in sync with what is really displayed.
 */
internal object VantageFullScreenState {
    var enabled: Boolean = false

    fun isImmersive(activity: Activity): Boolean {
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView) ?: return false
        return !insets.isVisible(WindowInsetsCompat.Type.systemBars())
    }

    fun sync(activity: Activity) {
        enabled = isImmersive(activity)
    }

    fun apply(activity: Activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Hide the system bars in the menu's own dialog window while full screen is active. */
    fun applyToWindow(window: Window?) {
        if (!enabled || window == null) return
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.vantageActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.vantageActivity()
        else -> null
    }

/**
 * Zoom row displayed inside the "more" menu: label, [-], percentage, [+] and a full screen button.
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
    val context = LocalContext.current
    val activity = remember(context) { context.vantageActivity() }

    var zoom by remember(key) { mutableStateOf(VantagePageZoomMemory.get(key)) }
    var fullScreen by remember { mutableStateOf(VantageFullScreenState.enabled) }

    LaunchedEffect(activity) {
        if (activity != null) {
            // Do not re-read the insets here: the menu window makes them look visible.
            // Just trust the toggle state and keep the bars hidden while the menu is open.
            fullScreen = VantageFullScreenState.enabled
            VantageFullScreenState.apply(activity)
        }
    }

    fun applyZoom(target: Float) {
        val value = (target * 10).roundToInt() / 10f
        val clamped = value.coerceIn(0.3f, 3.0f)
        zoom = clamped
        VantagePageZoomMemory.set(key, clamped)
        (engineSession as? GeckoEngineSession)?.setPageZoomFactor(clamped)
    }

    VantageScaledContent {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    // Vantage: keep the zoom controls as their own card, separated from the
                    // flat list of menu items rendered underneath.
                    .padding(vertical = 8.dp)
                    .clip(shape = RoundedCornerShape(12.dp))
                    .background(color = MaterialTheme.colorScheme.surfaceBright)
                    .defaultMinSize(minHeight = 52.dp)
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "缩放",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.weight(1f))

            ZoomCircleButton(symbol = "\u2212", onClick = { applyZoom(zoom - 0.1f) })

            Text(
                text = "${(zoom * 100).roundToInt()}%",
                modifier = Modifier.padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ZoomCircleButton(symbol = "+", onClick = { applyZoom(zoom + 0.1f) })

            Spacer(modifier = Modifier.size(width = 6.dp, height = 1.dp))

            IconButton(
                onClick = {
                    val targetActivity = activity ?: return@IconButton
                    fullScreen = !fullScreen
                    VantageFullScreenState.enabled = fullScreen
                    // Close the menu first, otherwise dismissing the dialog restores the system bars.
                    onFinish()
                    targetActivity.window.decorView.postDelayed(
                        {
                            VantageFullScreenState.apply(targetActivity)
                        },
                        250,
                    )
                },
                modifier =
                    Modifier.size(36.dp)
                        .clip(shape = RoundedCornerShape(8.dp))
                        .background(color = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_vantage_fullscreen),
                    contentDescription = "全屏",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomCircleButton(symbol: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier.size(36.dp)
                .clip(shape = CircleShape)
                .background(color = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Text(
            text = symbol,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
