/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R as materialR
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.usesVantageChrome
import org.mozilla.fenix.browser.browsingmode.BrowsingModeManager
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.utils.Settings
import org.mozilla.fenix.wallpapers.Wallpaper

/**
 * Feature responsible for managing window insets, background styling, and toolbar visibility during the home screen
 * lifecycle, when edge to edge background is enabled.
 *
 * @param appStore [AppStore] used for querying and updating application state.
 * @param activity The activity containing the window to manage.
 * @param settings The [Settings] used to determine the current position of the toolbar.
 * @param browsingModeManager The [BrowsingModeManager] used to determine the current browsing mode.
 * @param toolbarStore The [BrowserToolbarStore] which state is observed to manage status bar background in edit mode.
 * @param mainDispatcher The [CoroutineDispatcher] used for main thread operations.
 */
class HomepageEdgeToEdgeFeature(
    private val appStore: AppStore,
    private val activity: Activity,
    private val settings: Settings,
    private val browsingModeManager: BrowsingModeManager,
    private val toolbarStore: BrowserToolbarStore,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : LifecycleAwareFeature {

    private var backgroundView: View? = null
    private var statusBarHeight: Int = 0
    private var toolbarScope: CoroutineScope? = null
    private var wallpaperScope: CoroutineScope? = null

    override fun start() {
        if (!settings.enableHomepageEdgeToEdgeBackgroundFeature) {
            return
        }

        // Set the last known background before observing for updates to ensure the first drawn frame already shows the
        // right wallpaper.
        setWallpaper(appStore.state.wallpaperState.currentWallpaper)
        observeWallpaperUpdates()
    }

    private fun observeWallpaperUpdates() {
        wallpaperScope =
            appStore.flowScoped(dispatcher = mainDispatcher) { flow ->
                flow
                    .map { state -> state.wallpaperState.currentWallpaper }
                    .distinctUntilChanged()
                    .collect { wallpaper ->
                        setWallpaper(wallpaper)
                    }
            }
    }

    override fun stop() {
        removeEdgeToEdgeComponents()
        wallpaperScope?.cancel()
        wallpaperScope = null
    }

    private fun setWallpaper(wallpaper: Wallpaper) {
        if (wallpaper == Wallpaper.EdgeToEdge) {
            if (backgroundView == null) {
                setupStatusBarBackground()
            }
            setBackground(Background.HomeEdgeToEdge)
        } else {
            removeEdgeToEdgeComponents()
        }
    }

    private fun removeEdgeToEdgeComponents() {
        setBackground(Background.Regular)
        activity.window?.decorView?.let { decorView ->
            (decorView as? ViewGroup)?.apply {
                backgroundView?.let { view ->
                    this@apply.removeView(view)
                }
            }
        }
        toolbarScope?.cancel()
        toolbarScope = null
        backgroundView = null
    }

    private fun setBackground(background: Background) {
        // Vantage: private browsing uses the same window background as normal browsing, so the
        // white/dark theme keeps its colour instead of switching to the stock purple surface.
        activity.window?.setBackgroundDrawableResource(background.resourceId)
    }

    /**
     * Sets up a dynamic status bar background view that changes color based on toolbar state.
     *
     * This view is added directly to the DecorView (the root view of the activity window) to ensure it sits behind all
     * other content but can still receive window insets.
     *
     * The status bar height is not always known yet when this runs - on a newly created window the root insets are only
     * available once it is attached. The view is added at whatever height is known and resizes itself when insets
     * arrive; the window background does not wait on it, as it covers the whole window and so does not depend on the
     * status bar height.
     */
    private fun setupStatusBarBackground() {
        val rootView = activity.window?.decorView as? ViewGroup ?: return

        val rootInsetsTop =
            ViewCompat.getRootWindowInsets(rootView)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top
        if (rootInsetsTop != null) {
            statusBarHeight = rootInsetsTop
        }

        backgroundView =
            View(activity).apply {
                id = View.generateViewId()
                applyStatusBarBackground(this, toolbarStore.state)

                val params =
                    FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        gravity = Gravity.TOP
                        height = statusBarHeight
                    }
                rootView.addView(this, params)

                ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                    applyStatusBarInsets(this, insets)
                    insets
                }
            }
        if (statusBarHeight <= 0) {
            backgroundView?.let(ViewCompat::requestApplyInsets)
        }

        if (toolbarScope == null) {
            toolbarScope =
                toolbarStore.flowScoped(dispatcher = mainDispatcher) { flow ->
                    flow.collect { toolbarState ->
                        backgroundView?.let { applyStatusBarBackground(it, toolbarState) }
                    }
                }
        }
    }

    private fun applyStatusBarInsets(view: View, insets: WindowInsetsCompat) {
        statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        view.layoutParams.height = statusBarHeight
        view.requestLayout()
    }

    /**
     * Vantage: paints the status bar area with the same chrome as the home tab strip / toolbar so the
     * wallpaper does not show through above the top bar. Falls back to the upstream colour otherwise.
     */
    private fun applyStatusBarBackground(view: View, toolbarState: BrowserToolbarState) {
        val isNightMode =
            (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val useVantageChrome = usesVantageChrome(activity)

        if (!useVantageChrome) {
            view.setBackgroundColor(getStatusBarColor(settings, toolbarState))
            return
        }

        view.background =
            if (settings.shouldShowTabStripAtTop && !toolbarState.isShowingResultsScreen) {
                // Vantage: the tab strip gradient continues up into the status bar.
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    if (isNightMode) {
                        intArrayOf(VANTAGE_TAB_STRIP_START, VANTAGE_TAB_STRIP_END)
                    } else {
                        intArrayOf(VANTAGE_LIGHT_TAB_STRIP_START, VANTAGE_LIGHT_TAB_STRIP_END)
                    },
                )
            } else {
                ColorDrawable(if (isNightMode) VANTAGE_CHROME_SURFACE else VANTAGE_LIGHT_CHROME_SURFACE)
            }
    }

    private fun getStatusBarColor(settings: Settings, toolbarState: BrowserToolbarState): Int {
        val shouldShow = !settings.shouldUseBottomToolbar || toolbarState.isShowingResultsScreen

        // Vantage: private browsing is treated like normal browsing for the status bar chrome.
        return when {
            !shouldShow -> Color.TRANSPARENT
            !settings.isTabStripEnabled &&
                toolbarState.isShowingResultsScreen &&
                (settings.enableHomepageTrendingRecentSearch ||
                    toolbarState.editState.query.current.isNotEmpty() ||
                    toolbarState.editState.queryWasPrefilled) ->
                MaterialColors.getColor(
                    activity,
                    materialR.attr.colorSurface,
                    "Could not resolve color",
                )
            else -> ContextCompat.getColor(activity, R.color.homepage_tab_edge_to_edge_toolbar_background)
        }
    }

    private val BrowserToolbarState.isShowingResultsScreen: Boolean
        get() = isEditMode()

    /** Enum representing the available background drawable resources. */
    enum class Background(val resourceId: Int) {
        Regular(R.color.fx_mobile_surface),
        HomeEdgeToEdge(R.drawable.home_background_gradient),
    }

    companion object {
        // Vantage: desktop-style tab strip gradient endpoints and the chrome surface of the toolbar.
        private val VANTAGE_TAB_STRIP_START = 0xFF16406E.toInt()
        private val VANTAGE_TAB_STRIP_END = 0xFF166B47.toInt()
        private val VANTAGE_LIGHT_TAB_STRIP_START = 0xFF79A9F7.toInt()
        private val VANTAGE_LIGHT_TAB_STRIP_END = 0xFF55CDB4.toInt()
        private val VANTAGE_CHROME_SURFACE = 0xFF23222B.toInt()
        private val VANTAGE_LIGHT_CHROME_SURFACE = 0xFFF4F3F7.toInt()
    }
}
