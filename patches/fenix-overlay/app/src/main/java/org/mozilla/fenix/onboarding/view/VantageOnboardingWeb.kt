/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.onboarding.view

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.getPreferenceKey
import org.mozilla.fenix.theme.setVantageLightThemeSelected
import org.mozilla.fenix.theme.setVantageThemeSelected
import kotlin.math.roundToInt

private const val ONBOARDING_URL = "file:///android_asset/onboarding/index.html"
private const val JS_INTERFACE = "vantageOnboarding"
private const val ADDONS_URL = "https://addons.mozilla.org/android/"
private val webLogger = Logger("VantageOnboardingWeb")

/**
 * Vantage: the onboarding flow is a bundled web page so it can match the desktop
 * implementation. The page talks to [VantageOnboardingBridge] for the settings that
 * exist on Android; anything without an Android counterpart is simply not rendered.
 */
@Composable
fun VantageOnboardingWeb(onFinish: () -> Unit) {
    val context = LocalContext.current

    // The system adds a translucent scrim behind the bars when it thinks contrast is low.
    // That scrim is what makes the light theme look like it stops at the status/navigation bar.
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        if (window != null) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isNavigationBarContrastEnforced = false
        }
        onDispose { }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                val systemIsLight = (viewContext.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
                setBackgroundColor(
                    if (systemIsLight) 0xFFF0F1F6.toInt() else 0xFF1C1B22.toInt(),
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        webLogger.info("Onboarding page finished: $url")
                        if (view != null) applySystemInsets(view)
                        view?.evaluateJavascript(
                            "(function(){var h=document.documentElement,b=document.body,w=b.querySelector('.wrap')," +
                                "c=b.querySelector('.card'),s=b.querySelector('.step.active');" +
                                "function el(e){return e?{client:e.clientHeight,scroll:e.scrollHeight," +
                                "offset:e.offsetHeight}:null};" +
                                "return JSON.stringify({title:document.title,innerH:window.innerHeight," +
                                "html:el(h),body:el(b),wrap:el(w),card:el(c)," +
                                "bodyChars:b?b.innerText.length:-1," +
                                "activeDisplay:s?getComputedStyle(s).display:'missing'});})()",
                        ) { result -> webLogger.info("Onboarding DOM: $result") }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(view, request, error)
                        webLogger.error(
                            "Onboarding load error ${error?.errorCode}: ${error?.description}; " +
                                "url=${request?.url}",
                        )
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?,
                    ): Boolean {
                        webLogger.error("Onboarding renderer gone; crashed=${detail?.didCrash()}")
                        return false
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        webLogger.info(
                            "Onboarding JS ${message.messageLevel()}: ${message.message()} " +
                                "@${message.sourceId()}:${message.lineNumber()}",
                        )
                        return true
                    }
                }
                addJavascriptInterface(
                    VantageOnboardingBridge(context = viewContext, onFinish = onFinish),
                    JS_INTERFACE,
                )
                loadUrl(ONBOARDING_URL)
            }
        },
    )
}

/**
 * Hands the real status/navigation bar heights to the page.
 *
 * The activity draws edge-to-edge, so without this the card header slides under the status bar.
 * The Compose-level `WindowInsets` values can be consumed by the host, so we read them from the
 * WebView's own window insets instead. Values are converted to CSS pixels for the page.
 */
private fun applySystemInsets(view: WebView) {
    val insets = ViewCompat.getRootWindowInsets(view)
    if (insets == null) {
        webLogger.error("Onboarding insets unavailable")
        return
    }
    val density = view.resources.displayMetrics.density
    val top = (insets.getInsets(WindowInsetsCompat.Type.statusBars()).top / density).roundToInt()
    val bottom =
        (insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom / density).roundToInt()
    webLogger.info("Onboarding insets: top=$top bottom=$bottom (density=$density)")
    view.evaluateJavascript(
        "document.documentElement.style.setProperty('--inset-top','${top}px');" +
            "document.documentElement.style.setProperty('--inset-bottom','${bottom}px');",
        null,
    )
}

/**
 * Bridges the onboarding web page to the Android settings that back it.
 *
 * Instant preferences are written as soon as the switch is toggled. The theme choice is
 * buffered until the flow finishes, because changing the night mode recreates the activity
 * and would otherwise tear down the page the user is still looking at.
 */
private class VantageOnboardingBridge(
    private val context: Context,
    private val onFinish: () -> Unit,
) {
    private val logger = Logger("VantageOnboardingBridge")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = context.components.settings.preferences

    private var vantageThemeEnabled = true
    private var colorMode = COLOR_SYSTEM

    @JavascriptInterface
    fun getState(): String {
        logger.info("Reading onboarding state")
        return runCatching {
            JSONObject()
                .put("theme", vantageThemeEnabled)
                .put("colorMode", colorMode)
                .put("inactiveTabs", context.components.settings.inactiveTabsAreEnabled)
                .put("updater", context.components.settings.ironfox.updaterEnabled)
                .put("strict", isStrictTrackingProtectionEnabled())
                .put("doh", isDohEnabled())
                .put("defaultBrowser", isDefaultBrowser())
                .toString()
        }.onSuccess {
            logger.info("Onboarding state read: $it")
        }.getOrElse {
            logger.error("Unable to build onboarding state", it)
            "{}"
        }
    }

    @JavascriptInterface
    fun setTheme(enabled: Boolean) {
        logger.info("setTheme=$enabled")
        vantageThemeEnabled = enabled
    }

    @JavascriptInterface
    fun setColorMode(mode: String) {
        colorMode = when (mode) {
            COLOR_LIGHT, COLOR_DARK -> mode
            else -> COLOR_SYSTEM
        }
        logger.info("setColorMode=$colorMode")
        mainHandler.post { applyPageTheme(colorMode) }
    }

    /** Called by the page whenever its preview theme changes, including the initial sync. */
    @JavascriptInterface
    fun setPageTheme(mode: String) {
        logger.info("setPageTheme=$mode")
        mainHandler.post { applyPageTheme(mode) }
    }

    @JavascriptInterface
    fun setInactiveTabs(enabled: Boolean) {
        logger.info("setInactiveTabs=$enabled")
        context.components.settings.inactiveTabsAreEnabled = enabled
    }

    @JavascriptInterface
    fun setUpdater(enabled: Boolean) {
        logger.info("setUpdater=$enabled")
        context.components.settings.ironfox.updaterEnabled = enabled
    }

    @JavascriptInterface
    fun setStrictTracking(enabled: Boolean) {
        logger.info("setStrictTracking=$enabled")
        putBoolean(context.getPreferenceKey(R.string.pref_key_tracking_protection_strict_default), enabled)
    }

    @JavascriptInterface
    fun setDoh(enabled: Boolean) {
        logger.info("setDoh=$enabled")
        putBoolean(context.getPreferenceKey(R.string.pref_key_doh_settings_enabled), enabled)
    }

    @JavascriptInterface
    fun requestDefaultBrowser() {
        logger.info("requestDefaultBrowser")
        mainHandler.post {
            val activity = context.findActivity()
            if (activity == null) {
                logger.error("requestDefaultBrowser: no activity found from context")
                return@post
            }
            // The Android Q+ role request is unreliable on some OEM ROMs: MIUI's permission
            // controller receives a null calling package and closes the dialog immediately.
            // Opening the default-apps screen works across ROMs, so prefer it.
            val opened = runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }.isSuccess
            if (!opened) {
                logger.error("Unable to open default apps settings; falling back to app details")
                runCatching {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null),
                        ),
                    )
                }.onFailure { logger.error("Unable to open app details settings", it) }
            }
        }
    }

    @JavascriptInterface
    fun openAddonStore() {
        logger.info("openAddonStore")
        mainHandler.post {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(ADDONS_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { logger.error("Unable to open the add-on store", it) }
        }
    }

    @JavascriptInterface
    fun finish() {
        logger.info("finish called; colorMode=$colorMode vantageTheme=$vantageThemeEnabled")
        mainHandler.post {
            // Finish navigation first, then apply the theme. Applying the night mode recreates
            // the activity, so we delay it to let the fragment transaction commit.
            onFinish()
            mainHandler.postDelayed({ applyTheme() }, 300)
        }
    }

    private fun applyTheme() {
        val nightMode = when (colorMode) {
            COLOR_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            COLOR_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        // The branded palettes are selected explicitly so the choice survives the night-mode
        // change; following the system already resolves to the matching Vantage palette.
        when (colorMode) {
            COLOR_LIGHT -> {
                setVantageThemeSelected(context, false)
                setVantageLightThemeSelected(context, vantageThemeEnabled)
            }
            COLOR_DARK -> {
                setVantageThemeSelected(context, vantageThemeEnabled)
                setVantageLightThemeSelected(context, false)
            }
            else -> {
                setVantageThemeSelected(context, false)
                setVantageLightThemeSelected(context, false)
            }
        }
    }

    private fun isStrictTrackingProtectionEnabled(): Boolean =
        preferences.getBoolean(
            context.getPreferenceKey(R.string.pref_key_tracking_protection_strict_default),
            true,
        )

    private fun isDohEnabled(): Boolean =
        preferences.getBoolean(
            context.getPreferenceKey(R.string.pref_key_doh_settings_enabled),
            true,
        )

    private fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    private fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    /**
     * Paints the system bars with the page background so the light preview covers the whole
     * screen instead of stopping at the status and navigation bars.
     */
    private fun applyPageTheme(mode: String) {
        val activity = context.findActivity() ?: return
        val light = mode == COLOR_LIGHT ||
            (mode == COLOR_SYSTEM && (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_NO)
        val barColor = if (light) 0xFFF0F1F6.toInt() else 0xFF1C1B22.toInt()
        val window = activity.window
        @Suppress("DEPRECATION")
        window.statusBarColor = barColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = barColor
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        logger.info("applyPageTheme mode=$mode light=$light")
    }

    private companion object {
        const val COLOR_SYSTEM = "system"
        const val COLOR_LIGHT = "light"
        const val COLOR_DARK = "dark"
    }
}

/** Unwraps a Compose/ContextWrapper chain to find the hosting [Activity]. */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
