/* Vantage: functional search bar on the homepage with a real search engine switcher. */
package org.mozilla.fenix.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.action.SearchAction
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.state.searchEngines
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme

private val searchBarShape: Shape
    @Composable get() = MaterialTheme.shapes.extraLarge
private val IconBoxSize = 48.dp

/**
 * Homepage search bar: type to search with the selected engine, tap the engine icon to switch engines.
 *
 * @param onSearch invoked with the trimmed query when the user submits.
 */
@Composable
internal fun VantageHomeSearchBar(onSearch: (String) -> Unit) {
    val context = LocalContext.current
    val components = context.components
    val store = components.core.store

    var query by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf(store.state.search.selectedOrDefaultSearchEngine) }
    var showEnginePicker by remember { mutableStateOf(false) }

    // Vantage: an active desktop theme paints its image as the search bar background.
    val themeImage = rememberVantageThemeImage(isDark = isSystemInDarkTheme())

    Box(
        modifier =
            Modifier
                // Vantage: fixed width instead of stretching proportionally on tablets.
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = searchBarShape,
                )
                .clip(shape = searchBarShape),
    ) {
        if (themeImage != null) {
            Image(
                bitmap = themeImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // Keep the text readable regardless of which part of the image is visible.
            Box(
                modifier =
                    Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.20f)),
            )
        } else {
            Box(
                modifier =
                    Modifier.matchParentSize()
                        .background(color = MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(all = FirefoxTheme.layout.space.static50),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(size = IconBoxSize)
                    .clickable { showEnginePicker = true },
            contentAlignment = Alignment.Center,
        ) {
            val icon: android.graphics.Bitmap? = engine?.icon
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(size = 24.dp),
                )
            } else {
                Icon(
                    painter = painterResource(id = iconsR.drawable.mozac_ic_search_24),
                    tint = MaterialTheme.colorScheme.onSurface,
                    contentDescription = null,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = FirefoxTheme.layout.space.static50)
                    .heightIn(min = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = FirefoxTheme.typography.body1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = FirefoxTheme.typography.body1.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            val term = query.trim()
                            if (term.isNotEmpty()) {
                                onSearch(term)
                            }
                        }
                    ),
            )
        }

        if (query.isNotEmpty()) {
            TextButton(onClick = { query = "" }) {
                Text(text = "\u00d7")
            }
        }
    }
    }

    if (showEnginePicker) {
        val engines = store.state.search.searchEngines
        AlertDialog(
            onDismissRequest = { showEnginePicker = false },
            title = { Text(text = stringResource(R.string.preferences_search)) },
            text = {
                Column {
                    engines.forEach { candidate ->
                        EngineRow(
                            engine = candidate,
                            selected = candidate.id == engine?.id,
                            onClick = {
                                engine = candidate
                                store.dispatch(SearchAction.SelectSearchEngineAction(candidate.id, candidate.name))
                                showEnginePicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEnginePicker = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EngineRow(engine: SearchEngine, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = engine.icon
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.padding(end = 12.dp).size(size = 24.dp),
        )
        Text(
            text = engine.name,
            modifier = Modifier.weight(1f),
            color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        if (selected) {
            Text(text = "\u2713")
        }
    }
}
