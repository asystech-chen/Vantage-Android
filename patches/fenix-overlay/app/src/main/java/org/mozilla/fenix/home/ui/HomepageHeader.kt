/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.button.IconButton
import mozilla.components.compose.base.theme.PreviewThemeProvider
import mozilla.components.compose.base.theme.Theme
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.home.ui.HomepageTestTag.PRIVATE_BROWSING_HOMEPAGE_BUTTON
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.wallpapers.WallpaperTheme

/** Vantage brand blue used for the private-browsing mask icon in the homepage header. */
private val VANTAGE_PRIVATE_ICON_BLUE = Color(0xFF4A8FF7)

/**
 * Header for the homepage.
 *
 * @param browsingMode The current [BrowsingMode].
 * @param showStoriesButton Whether to show the stories button or not.
 * @param browsingModeChanged Callback for when the browsing mode is toggled.
 * @param onStoriesTapped Callback for when the stories button is tapped.
 * @param logoSpacerHeight Vantage: extra space inserted between the top button row and the
 *  wordmark, so the private-browsing button can stay pinned to the top of the page while the
 *  logo + search block remains vertically centred.
 */
@Composable
fun HomepageHeader(
    browsingMode: BrowsingMode,
    showStoriesButton: Boolean,
    alwaysUsePB: Boolean,
    browsingModeChanged: (BrowsingMode) -> Unit,
    onStoriesTapped: () -> Unit,
    logoSpacerHeight: Dp = 0.dp,
) {
    // Vantage: private browsing keeps the same homepage appearance as normal mode, so the
    // wordmark and the browsing-mode button both follow the wallpaper text color. In private
    // mode the mask icon is tinted brand blue so it reads as the Vantage accent.
    val wordmarkTextColor = WallpaperTheme.onWallpaper
    val privateBrowsingButtonColor =
        if (browsingMode.isPrivate) {
            VANTAGE_PRIVATE_ICON_BLUE
        } else {
            WallpaperTheme.onWallpaper
        }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .wrapContentHeight()
                .padding(
                    bottom = FirefoxTheme.layout.space.static500,
                    top = FirefoxTheme.layout.space.static100,
                )
                .padding(horizontal = FirefoxTheme.layout.space.static100),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            verticalAlignment = Alignment.Top,
        ) {
            if (showStoriesButton) {
                StoriesButton(color = WallpaperTheme.onWallpaper, onClick = onStoriesTapped)
            }

            Spacer(modifier = Modifier.weight(1f))

            if (!alwaysUsePB) {
                PrivateBrowsingButton(
                    color = privateBrowsingButtonColor,
                    browsingMode = browsingMode,
                    browsingModeChanged = browsingModeChanged,
                )
            }
        }

        // Vantage: keep the browsing-mode button pinned to the top of the page and only push the
        // wordmark + search block down. This applies to private mode as well, so both modes share
        // the exact same layout.
        if (logoSpacerHeight > 0.dp) {
            Spacer(modifier = Modifier.height(logoSpacerHeight))
        }
        WordmarkAndLogo(wordmarkTextColor = wordmarkTextColor)
    }
}

@Composable
private fun StoriesButton(
    color: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, contentDescription = null) {
        Icon(
            tint = color,
            painter = painterResource(iconsR.drawable.mozac_ic_reading_list_24),
            contentDescription = stringResource(R.string.homepage_all_stories),
        )
    }
}

@Composable
private fun PrivateBrowsingButton(
    color: Color,
    browsingMode: BrowsingMode,
    browsingModeChanged: (BrowsingMode) -> Unit,
) {
    IconToggleButton(
        modifier =
            Modifier.background(
                    color = colorResource(getAttr(iconsR.attr.mozac_ic_private_mode_circle_fill_background_color)),
                    shape = CircleShape,
                )
                .semantics {
                    role = Role.Switch
                    testTagsAsResourceId = true
                    testTag = PRIVATE_BROWSING_HOMEPAGE_BUTTON
                },
        checked = browsingMode.isPrivate,
        onCheckedChange = {
            browsingModeChanged(BrowsingMode.fromBoolean(!browsingMode.isPrivate))
        },
    ) {
        Icon(
            tint = color,
            painter = painterResource(iconsR.drawable.mozac_ic_private_mode_24),
            contentDescription = stringResource(R.string.content_description_private_browsing),
        )
    }
}

@Composable
internal fun getAttr(resId: Int): Int {
    val typedArray = LocalContext.current.obtainStyledAttributes(intArrayOf(resId))
    val newResId = typedArray.getResourceId(0, 0)
    typedArray.recycle()

    return newResId
}

@Preview
@Composable
private fun HomepageHeaderPreview(@PreviewParameter(PreviewThemeProvider::class) theme: Theme) {
    FirefoxTheme(theme) {
        Surface {
            HomepageHeader(
                browsingMode = BrowsingMode.Normal,
                showStoriesButton = true,
                alwaysUsePB = false,
                browsingModeChanged = {},
                onStoriesTapped = {},
            )
        }
    }
}
