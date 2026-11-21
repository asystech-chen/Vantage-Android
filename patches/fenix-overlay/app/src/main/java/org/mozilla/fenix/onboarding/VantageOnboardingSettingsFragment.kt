/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.onboarding

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.navigation.fragment.findNavController
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.onboarding.view.VantageOnboardingWeb
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Vantage: reopens the bundled onboarding web page from `about:onboarding` so the same
 * settings can be reviewed and changed after the first run.
 */
class VantageOnboardingSettingsFragment : Fragment() {
    private var webView: WebView? = null
    private var isClosing = false
    private var popped = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closeFallback = Runnable { finishPage() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        FirefoxTheme {
            ScreenContent()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Back must go through the page: it applies the colour mode when it finishes, so
        // popping directly would leave the old night mode in place.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closePage()
                }
            },
        )
    }

    @Composable
    private fun ScreenContent() {
        VantageOnboardingWeb(
            settingsMode = true,
            onFinish = { finishPage() },
            onWebViewReady = { webView = it },
        )
    }

    private fun closePage() {
        if (isClosing) return
        isClosing = true
        val page = webView
        if (page == null) {
            finishPage()
        } else {
            page.evaluateJavascript(
                "window.vantageOnboarding && window.vantageOnboarding.finish()",
                null,
            )
            // The page normally closes us through the bridge; this keeps back working if the
            // page has not finished loading and the interface is not reachable yet.
            mainHandler.postDelayed(closeFallback, 400)
        }
    }

    /** Exits once, whichever path closes the page first. */
    private fun finishPage() {
        if (popped) return
        popped = true
        findNavController().popBackStack()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(closeFallback)
        webView = null
        super.onDestroyView()
        // The page paints the system bars with its own background. Restore the browser theme
        // when it closes so the bars do not keep the page colour.
        (activity as? HomeActivity)?.let { activity ->
            activity.themeManager.applyStatusBarTheme(activity)
        }
    }
}
