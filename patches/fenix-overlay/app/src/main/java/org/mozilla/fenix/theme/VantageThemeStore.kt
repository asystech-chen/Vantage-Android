/* Vantage: desktop Firefox theme (AMO XPI) storage and palette conversion. */
package org.mozilla.fenix.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.compose.base.theme.AcornGradient
import mozilla.components.compose.base.theme.AcornGradientScheme
import mozilla.components.compose.base.theme.AcornGradientType
import mozilla.components.compose.base.theme.darkAcornGradientScheme
import mozilla.components.compose.base.theme.lightAcornGradientScheme
import mozilla.components.compose.base.utils.ColorStop
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "vantage_desktop_themes"
private const val KEY_THEMES = "vantage_themes_json"
private const val KEY_ACTIVE = "vantage_active_theme"
private const val THEMES_DIRECTORY = "vantage_themes"
private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
private const val MAX_IMAGES = 16
private const val AMO_SEARCH_URL =
    "https://addons.mozilla.org/api/v5/addons/search/?app=firefox&type=statictheme"

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "svg")

/** Vantage's branded blues, kept for buttons regardless of the desktop theme. */
private val VANTAGE_BLUE = Color(0xFF4A8FF7)
private val VANTAGE_BLUE_LIGHT = Color(0xFF3B82F6)
private val VANTAGE_BLUE_DARK = Color(0xFF2457C5)

/** Decoded background image plus the dominant colour sampled from it. */
private data class SavedBackground(val fileName: String, val dominantColor: Int?)

/** A single light/dark flavour of an installed desktop theme. */
data class VantageThemeVariant(
    /** Whether this flavour is meant for a dark UI. */
    val isDark: Boolean,
    /** Background image file name inside the theme's storage directory, if any. */
    val backgroundFile: String?,
    /** Raw desktop theme colour keys (`frame`, `toolbar`, `tab_line`, ...). */
    val desktopColors: Map<String, String>,
    /** Dominant colour of the background image, used as the homepage background. */
    val homeColor: Int? = null,
)

/** An installed Firefox desktop theme, converted into a Vantage-compatible palette. */
data class VantageTheme(
    val id: String,
    val name: String,
    val author: String,
    /** Whether the theme should default to the dark appearance when enabled. */
    val defaultIsDark: Boolean,
    val variants: List<VantageThemeVariant>,
) {
    val hasBackground: Boolean
        get() = variants.any { it.backgroundFile != null }
}

/** A single result from the AMO desktop theme search. */
data class VantageThemeSearchResult(
    val id: String,
    val name: String,
    val author: String,
    val downloadUrl: String,
)

/** Compose palettes generated for the currently active desktop theme. */
data class VantageThemePalette(
    val colorScheme: ColorScheme,
    val gradientScheme: AcornGradientScheme,
    val chrome: Color,
    val homeBackground: Color,
    val accent: Color,
    val secondary: Color,
)

/**
 * Stores desktop Firefox themes installed from addons.mozilla.org and turns their
 * `theme` / `dark_theme` colour keys into Compose palettes.
 *
 * Themes live in `filesDir/vantage_themes/<id>/` with their background image, the metadata is
 * saved as JSON in [SharedPreferences], and the active theme id is kept alongside it.
 */
class VantageThemeStore private constructor(private val appContext: Context) {

    private val preferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()
    private var themesCache: List<VantageTheme>? = null
    private var activeIdCache: String? = null
    private var activeIdLoaded = false

    private val paletteCache = HashMap<String, VantageThemePalette>()

    /** Themes installed by the user, in installation order. */
    val installedThemes: List<VantageTheme>
        get() = synchronized(lock) {
            themesCache ?: loadThemes().also { themesCache = it }
        }

    /** The id of the theme currently selected in the theme list, or `null` for a built-in theme. */
    val activeThemeId: String?
        get() = synchronized(lock) {
            if (!activeIdLoaded) {
                activeIdCache = preferences.getString(KEY_ACTIVE, null)
                activeIdLoaded = true
            }
            activeIdCache
        }

    /** The currently enabled desktop theme, if any. */
    val activeTheme: VantageTheme?
        get() {
            val id = activeThemeId ?: return null
            return installedThemes.firstOrNull { it.id == id }
        }

    /** Selects [id], or clears the custom selection when [id] is `null`. */
    fun setActiveTheme(id: String?) {
        val effective = id?.takeIf { candidate -> installedThemes.any { it.id == candidate } }
        synchronized(lock) {
            activeIdCache = effective
            activeIdLoaded = true
        }
        preferences.edit().apply {
            if (effective == null) {
                remove(KEY_ACTIVE)
            } else {
                putString(KEY_ACTIVE, effective)
            }
        }.apply()
    }

    /** Deletes an installed theme, its background image, and clears it if it was active. */
    fun deleteTheme(id: String) {
        val remaining = installedThemes.filterNot { it.id == id }
        synchronized(lock) {
            themesCache = remaining
        }
        saveThemes(remaining)
        themeDirectory(id).deleteRecursively()
        if (activeThemeId == id) {
            setActiveTheme(null)
        }
        synchronized(lock) {
            paletteCache.keys.removeAll { it.startsWith("$id:") }
        }
    }

    /**
     * Parses a desktop theme XPI and stores it locally.
     *
     * @param bytes The raw XPI contents.
     * @param displayName An optional localized name (e.g. from the AMO search result).
     * @param authorOverride An optional author name (e.g. from the AMO search result).
     */
    suspend fun installFromXpi(
        bytes: ByteArray,
        displayName: String? = null,
        authorOverride: String? = null,
    ): VantageTheme = withContext(Dispatchers.IO) {
        var manifestJson: JSONObject? = null
        val images = mutableMapOf<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (entryName == "manifest.json") {
                    manifestJson =
                        JSONObject(
                            zip.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF"),
                        )
                } else if (!entry.isDirectory && images.size < MAX_IMAGES) {
                    val extension = entryName.substringAfterLast('.', "").lowercase()
                    if (extension in IMAGE_EXTENSIONS) {
                        val data = zip.readBytes()
                        if (data.size <= MAX_IMAGE_BYTES) {
                            images[entryName] = data
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val manifest = manifestJson ?: throw IOException("Theme XPI has no manifest.json")

        val rawId = manifest.themeId()
        val themeId = sanitizeId(rawId.ifBlank { displayName ?: "theme" })
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: manifest.localizedName().takeIf { it.isNotBlank() }
            ?: themeId
        val author = authorOverride?.takeIf { it.isNotBlank() }
            ?: manifest.themeAuthor().takeIf { it.isNotBlank() }
            ?: ""

        val directory = themeDirectory(themeId)
        directory.deleteRecursively()
        directory.mkdirs()

        val lightBlock = manifest.optJSONObject("theme")
        val darkBlock = manifest.optJSONObject("dark_theme")
        if (lightBlock == null && darkBlock == null) {
            throw IOException("Theme manifest has neither theme nor dark_theme")
        }

        val defaultIsDark =
            if (lightBlock == null) {
                true
            } else {
                lightBlock.themeFrameColor()?.let(::isDarkColor) ?: false
            }

        val variants = mutableListOf<VantageThemeVariant>()
        if (lightBlock != null) {
            val background =
                saveBackground(lightBlock.imageReference(), images, directory, "background.png")
            variants += VantageThemeVariant(
                isDark = if (darkBlock == null) defaultIsDark else false,
                backgroundFile = background?.fileName,
                desktopColors = lightBlock.readDesktopColors(),
                homeColor = background?.dominantColor,
            )
        }
        if (darkBlock != null) {
            val background =
                saveBackground(darkBlock.imageReference(), images, directory, "background_dark.png")
            variants += VantageThemeVariant(
                isDark = true,
                backgroundFile = background?.fileName,
                desktopColors = darkBlock.readDesktopColors(),
                homeColor = background?.dominantColor,
            )
        }

        val theme = VantageTheme(
            id = themeId,
            name = name,
            author = author,
            defaultIsDark = defaultIsDark,
            variants = variants,
        )

        val updated = installedThemes.filterNot { it.id == themeId } + theme
        synchronized(lock) {
            themesCache = updated
            paletteCache.keys.removeAll { it.startsWith("$themeId:") }
        }
        saveThemes(updated)
        theme
    }

    /** Searches addons.mozilla.org for desktop static themes. Network work runs on [Dispatchers.IO]. */
    suspend fun searchThemes(query: String): List<VantageThemeSearchResult> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()

            val url = URL(
                "$AMO_SEARCH_URL&q=${URLEncoder.encode(trimmed, "UTF-8")}" +
                    "&page_size=20&lang=zh-CN",
            )
            val body = request(url, accept = "application/json").toString(Charsets.UTF_8)
            val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
            val themes = mutableListOf<VantageThemeSearchResult>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val name = item.localizedName().ifBlank { item.optString("slug", "") }
                val author =
                    item.optJSONArray("authors")?.optJSONObject(0)?.optString("name", "").orEmpty()
                val downloadUrl =
                    item.optJSONObject("current_version")
                        ?.optJSONObject("file")
                        ?.optString("url", "")
                        .orEmpty()
                val id = item.optString("guid", "").ifBlank { item.optString("slug", "") }
                if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                    themes += VantageThemeSearchResult(
                        id = id,
                        name = name,
                        author = author,
                        downloadUrl = downloadUrl,
                    )
                }
            }
            themes
        }

    /** Downloads an XPI from [url] on [Dispatchers.IO]. */
    suspend fun downloadThemeXpi(url: String): ByteArray = withContext(Dispatchers.IO) {
        request(URL(url), accept = "*/*")
    }

    /** Loads the background image of the active theme for the given appearance, if it has one. */
    fun loadActiveBackgroundBitmap(isDark: Boolean): Bitmap? {
        val theme = activeTheme ?: return null
        val variant = variantFor(theme, isDark)
        val fileName = variant.backgroundFile ?: return null
        val file = File(themeDirectory(theme.id), fileName)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** The chrome (title bar / toolbar) colour of the active theme as an ARGB int. */
    fun activeChromeColor(isDark: Boolean): Int? {
        val theme = activeTheme ?: return null
        return paletteFor(theme, isDark).chrome.toArgb()
    }

    /** Builds (and caches) the Compose palettes for [theme]. */
    fun paletteFor(theme: VantageTheme, dark: Boolean): VantageThemePalette {
        val cacheKey = "${theme.id}:$dark"
        synchronized(lock) {
            paletteCache[cacheKey]?.let { return it }
        }
        val palette = buildPalette(theme, dark)
        synchronized(lock) {
            paletteCache[cacheKey] = palette
        }
        return palette
    }

    private fun variantFor(theme: VantageTheme, dark: Boolean): VantageThemeVariant =
        theme.variants.firstOrNull { it.isDark == dark } ?: theme.variants.first()

    private fun themeDirectory(id: String) = File(File(appContext.filesDir, THEMES_DIRECTORY), id)

    private fun loadThemes(): List<VantageTheme> {
        val raw = preferences.getString(KEY_THEMES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val themes = mutableListOf<VantageTheme>()
            for (index in 0 until array.length()) {
                val theme = array.optJSONObject(index)?.toTheme() ?: continue
                // Themes installed before the dominant-colour field existed get it computed
                // from their stored background image on first load.
                val migrated =
                    theme.copy(
                        variants =
                            theme.variants.map { variant ->
                                if (variant.homeColor != null || variant.backgroundFile == null) {
                                    variant
                                } else {
                                    val file =
                                        themeDirectory(theme.id).resolve(variant.backgroundFile)
                                    val bitmap =
                                        runCatching {
                                            BitmapFactory.decodeFile(file.absolutePath)
                                        }.getOrNull()
                                    variant.copy(homeColor = bitmap?.let { dominantColorOf(it) })
                                }
                            },
                    )
                themes += migrated
            }
            themes
        }.getOrDefault(emptyList())
    }

    private fun saveThemes(themes: List<VantageTheme>) {
        val array = JSONArray()
        themes.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_THEMES, array.toString()).apply()
    }

    private fun buildPalette(theme: VantageTheme, dark: Boolean): VantageThemePalette {
        val variant = variantFor(theme, dark)
        val frame = variant.color("frame") ?: if (dark) Color(0xFF1C1B22) else Color(0xFFFAF9FC)
        val toolbar = variant.color("toolbar") ?: frame
        val toolbarText =
            variant.color("toolbar_text")
                ?: variant.color("tab_background_text")
                ?: variant.color("toolbar_field_text")
                ?: autoTextColor(toolbar)
        val ntpText = variant.color("ntp_text") ?: toolbarText
        val ntpBackground = variant.color("ntp_background") ?: frame
        val toolbarField = variant.color("toolbar_field") ?: blend(toolbar, toolbarText, 0.08f)
        val toolbarFieldBorder =
            variant.color("toolbar_field_border") ?: blend(toolbarField, toolbarText, 0.18f)
        val popup = variant.color("popup") ?: blend(toolbar, frame, 0.5f)
        val popupText = variant.color("popup_text") ?: blend(toolbarText, popup, 0.25f)
        // Vantage: actions stay branded blue instead of taking over the desktop theme's accent.
        val primary = if (dark) VANTAGE_BLUE else VANTAGE_BLUE_LIGHT
        val secondary = if (dark) VANTAGE_BLUE_LIGHT else VANTAGE_BLUE_DARK

        // Vantage: the homepage uses the dominant colour of the header image rather than the
        // image itself; the image is only painted behind the tab strip and the search bar.
        val dominant = variant.homeColor?.let { Color(it) } ?: ntpBackground
        val onSurface = autoTextColor(dominant)
        val onBackground = onSurface
        val surface = dominant
        val background = dominant
        val surfaceContainerHigh = toolbarField
        val outline = toolbarFieldBorder

        val colorScheme =
            if (dark) {
                darkColorScheme(
                    primary = primary,
                    onPrimary = autoTextColor(primary),
                    primaryContainer = darken(primary, 0.35f),
                    onPrimaryContainer = lighten(primary, 0.7f),
                    secondary = secondary,
                    onSecondary = autoTextColor(secondary),
                    secondaryContainer = darken(secondary, 0.4f),
                    onSecondaryContainer = lighten(secondary, 0.75f),
                    tertiary = primary,
                    onTertiary = autoTextColor(primary),
                    tertiaryContainer = darken(primary, 0.35f),
                    onTertiaryContainer = lighten(primary, 0.7f),
                    background = background,
                    onBackground = onBackground,
                    surface = surface,
                    onSurface = onSurface,
                    surfaceVariant = surfaceContainerHigh,
                    onSurfaceVariant = popupText,
                    surfaceContainerLowest = darken(background, 0.2f),
                    surfaceContainerLow = darken(background, 0.08f),
                    surfaceContainer = popup,
                    surfaceContainerHigh = surfaceContainerHigh,
                    surfaceContainerHighest = lighten(surface, 0.14f),
                    surfaceBright = lighten(surface, 0.18f),
                    surfaceDim = darken(background, 0.12f),
                    outline = outline,
                    outlineVariant = blend(outline, surface, 0.4f),
                    error = Color(0xFFFF6B6B),
                    onError = Color(0xFF2A0000),
                    inversePrimary = primary,
                )
            } else {
                lightColorScheme(
                    primary = primary,
                    onPrimary = autoTextColor(primary),
                    primaryContainer = lighten(primary, 0.75f),
                    onPrimaryContainer = darken(primary, 0.6f),
                    secondary = secondary,
                    onSecondary = autoTextColor(secondary),
                    secondaryContainer = lighten(secondary, 0.8f),
                    onSecondaryContainer = darken(secondary, 0.6f),
                    tertiary = primary,
                    onTertiary = autoTextColor(primary),
                    tertiaryContainer = lighten(primary, 0.75f),
                    onTertiaryContainer = darken(primary, 0.6f),
                    background = background,
                    onBackground = onBackground,
                    surface = surface,
                    onSurface = onSurface,
                    surfaceVariant = surfaceContainerHigh,
                    onSurfaceVariant = popupText,
                    surfaceContainerLowest = lighten(background, 0.06f),
                    surfaceContainerLow = lighten(background, 0.03f),
                    surfaceContainer = popup,
                    surfaceContainerHigh = surfaceContainerHigh,
                    surfaceContainerHighest = darken(surface, 0.1f),
                    surfaceBright = lighten(surface, 0.3f),
                    surfaceDim = darken(background, 0.12f),
                    outline = outline,
                    outlineVariant = blend(outline, surface, 0.4f),
                    error = Color(0xFFB3261E),
                    onError = Color(0xFFFFFFFF),
                    inversePrimary = primary,
                )
            }

        val accentGradient =
            AcornGradient(
                type = AcornGradientType.Linear(angleInDegrees = 96f),
                colorStops = listOf(ColorStop(0f, primary), ColorStop(1f, secondary)),
            )
        val accentSubtleGradient =
            AcornGradient(
                type = AcornGradientType.Linear(angleInDegrees = 90f),
                colorStops = listOf(ColorStop(0f, dominant), ColorStop(1f, primary)),
            )
        val baseGradientScheme =
            if (dark) darkAcornGradientScheme else lightAcornGradientScheme

        return VantageThemePalette(
            colorScheme = colorScheme,
            gradientScheme = AcornGradientScheme(
                cfr = baseGradientScheme.cfr,
                accent = accentGradient,
                accentSubtle = accentSubtleGradient,
                tabOutline = accentGradient,
                privacyMask = baseGradientScheme.privacyMask,
            ),
            chrome = dominant,
            homeBackground = dominant,
            accent = primary,
            secondary = secondary,
        )
    }

    companion object {
        @Volatile
        private var instance: VantageThemeStore? = null

        fun get(context: Context): VantageThemeStore =
            instance ?: synchronized(this) {
                instance ?: VantageThemeStore(context.applicationContext).also { instance = it }
            }

        private fun saveBackground(
            reference: String?,
            images: Map<String, ByteArray>,
            directory: File,
            fileName: String,
        ): SavedBackground? {
            val data = reference?.let { findImageData(it, images) } ?: return null
            val bitmap = decodeImage(reference, data) ?: return null
            val saved = runCatching {
                File(directory, fileName).outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                fileName
            }.getOrNull() ?: return null
            return SavedBackground(saved, dominantColorOf(bitmap))
        }

        /**
         * Samples the image on a small grid and returns the most common quantised colour, skipping
         * transparent pixels. Falls back to the average colour when the image is fully transparent.
         */
        private fun dominantColorOf(bitmap: Bitmap): Int? {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val sampleWidth = 48
            val sampleHeight = 48
            val pixels = IntArray(sampleWidth * sampleHeight)
            val scaled = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
            scaled.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
            if (scaled !== bitmap) {
                scaled.recycle()
            }

            val buckets = HashMap<Int, Int>()
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var opaqueCount = 0
            for (pixel in pixels) {
                val alpha = pixel ushr 24 and 0xFF
                if (alpha < 200) continue
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF
                val key = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
                buckets[key] = (buckets[key] ?: 0) + 1
                sumR += red
                sumG += green
                sumB += blue
                opaqueCount++
            }
            if (opaqueCount == 0) return null

            val winningKey = buckets.maxByOrNull { it.value }?.key ?: return null
            var winningR = 0
            var winningG = 0
            var winningB = 0
            var winningCount = 0
            for (pixel in pixels) {
                val alpha = pixel ushr 24 and 0xFF
                if (alpha < 200) continue
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF
                val key = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
                if (key == winningKey) {
                    winningR += red
                    winningG += green
                    winningB += blue
                    winningCount++
                }
            }
            val red: Int
            val green: Int
            val blue: Int
            if (winningCount > 0) {
                red = winningR / winningCount
                green = winningG / winningCount
                blue = winningB / winningCount
            } else {
                red = sumR / opaqueCount
                green = sumG / opaqueCount
                blue = sumB / opaqueCount
            }
            return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        }

        private fun findImageData(reference: String, images: Map<String, ByteArray>): ByteArray? {
            val normalized = reference.trimStart('/')
            return images[normalized]
                ?: images.entries.firstOrNull { it.key.endsWith(normalized) }?.value
                ?: images.entries.firstOrNull {
                    it.key.substringAfterLast('/') == normalized.substringAfterLast('/')
                }?.value
        }

        private fun decodeImage(reference: String, data: ByteArray): Bitmap? {
            if (!reference.endsWith(".svg", ignoreCase = true)) {
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { return it }
            }
            return renderSvg(data)
        }

        /**
         * Rasterizes an SVG using AndroidSVG when it is present at runtime. The app module does
         * not compile against the library directly (browser-icons owns it), so this is reflective
         * and silently skips the image when it is unavailable.
         */
        private fun renderSvg(data: ByteArray): Bitmap? = try {
            val svgClass = Class.forName("com.caverock.androidsvg.SVG")
            val svg = svgClass
                .getMethod("getFromInputStream", InputStream::class.java)
                .invoke(null, ByteArrayInputStream(data))
            if (svg == null) {
                null
            } else {
                val render = svgClass.getMethod(
                    "renderToBitmap",
                    java.lang.Integer.TYPE,
                    java.lang.Integer.TYPE,
                )
                render.invoke(svg, 1080, 1920) as? Bitmap
            }
        } catch (throwable: Throwable) {
            null
        }

        private fun sanitizeId(raw: String): String {
            val cleaned = raw
                .lowercase()
                .replace(Regex("[^a-z0-9._-]"), "_")
                .trim('.', '_')
                .take(48)
            return cleaned.ifEmpty { "theme_${System.currentTimeMillis()}" }
        }

        private fun request(url: URL, accept: String): ByteArray {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "Vantage-Android")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code for $url")
                return connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/**
 * Reads a name field that addons.mozilla.org returns either as a plain string or as a map of
 * locale -> name (the latter is common in the search API).
 */
private fun JSONObject.localizedName(): String {
    (opt("name") as? String)?.takeIf { it.isNotBlank() }?.let { return it }
    val names = optJSONObject("name") ?: return ""
    return names.optString("zh-CN", "").takeIf { it.isNotBlank() }
        ?: names.optString("_default", "").takeIf { it.isNotBlank() }
        ?: names.keys().asSequence()
            .mapNotNull { key -> names.optString(key, "").takeIf { it.isNotBlank() } }
            .firstOrNull()
        .orEmpty()
}

private fun JSONObject.themeId(): String {
    val gecko =
        optJSONObject("browser_specific_settings")?.optJSONObject("gecko")
            ?: optJSONObject("applications")?.optJSONObject("gecko")
    return gecko?.optString("id", "").orEmpty()
}

private fun JSONObject.themeAuthor(): String =
    optString("author", "").ifBlank {
        optJSONArray("authors")?.optJSONObject(0)?.optString("name", "").orEmpty()
    }

private fun JSONObject.readDesktopColors(): Map<String, String> {
    val colors = mutableMapOf<String, String>()
    optJSONObject("colors")?.let { json ->
        json.keys().forEach { key ->
            val value = json.optString(key, "")
            if (value.isNotBlank()) {
                colors[key] = value
            }
        }
    }
    return colors
}

private fun JSONObject.imageReference(): String? {
    val images = optJSONObject("images") ?: return null
    val additional = images.optJSONArray("additional_backgrounds")
    if (additional != null && additional.length() > 0) {
        additional.optString(0, "").takeIf { it.isNotBlank() }?.let { return it }
    }
    return images.optString("theme_frame", "").takeIf { it.isNotBlank() }
        ?: images.optString("headerURL", "").takeIf { it.isNotBlank() }
}

private fun JSONObject.themeFrameColor(): Color? =
    optJSONObject("colors")?.optString("frame", "")?.let(::parseCssColor)

private fun VantageThemeVariant.color(key: String): Color? =
    parseCssColor(desktopColors[key])

private fun VantageTheme.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("author", author)
    put("defaultIsDark", defaultIsDark)
    put(
        "variants",
        JSONArray().apply {
            variants.forEach { variant ->
                put(
                    JSONObject().apply {
                        put("isDark", variant.isDark)
                        put("backgroundFile", variant.backgroundFile ?: "")
                        variant.homeColor?.let { put("homeColor", it) }
                        put("colors", JSONObject(variant.desktopColors))
                    },
                )
            }
        },
    )
}

private fun JSONObject.toTheme(): VantageTheme? {
    val id = optString("id", "")
    if (id.isBlank()) return null
    val variantsJson = optJSONArray("variants") ?: JSONArray()
    val variants = mutableListOf<VantageThemeVariant>()
    for (index in 0 until variantsJson.length()) {
        val variant = variantsJson.optJSONObject(index) ?: continue
        val colors = mutableMapOf<String, String>()
        variant.optJSONObject("colors")?.let { json ->
            json.keys().forEach { key ->
                val value = json.optString(key, "")
                if (value.isNotBlank()) {
                    colors[key] = value
                }
            }
        }
        variants += VantageThemeVariant(
            isDark = variant.optBoolean("isDark", false),
            backgroundFile = variant.optString("backgroundFile", "")
                .takeIf { it.isNotBlank() },
            desktopColors = colors,
            homeColor = if (variant.has("homeColor")) variant.optInt("homeColor") else null,
        )
    }
    if (variants.isEmpty()) return null
    return VantageTheme(
        id = id,
        name = optString("name", id),
        author = optString("author", ""),
        defaultIsDark = optBoolean("defaultIsDark", variants.first().isDark),
        variants = variants,
    )
}

/** Parses the CSS colour formats used by desktop theme manifests. */
private fun parseCssColor(raw: String?): Color? {
    val value = raw?.trim()?.lowercase() ?: return null
    if (value.isEmpty()) return null

    return when {
        value.startsWith("#") -> parseHexColor(value.substring(1))
        value.startsWith("rgb") -> parseRgbColor(value)
        value.startsWith("hsl") -> parseHslColor(value)
        value == "transparent" -> Color.Transparent
        value == "white" -> Color(0xFFFFFFFF)
        value == "black" -> Color(0xFF000000)
        value == "gray" || value == "grey" -> Color(0xFF808080)
        value == "silver" -> Color(0xFFC0C0C0)
        value == "red" -> Color(0xFFFF0000)
        value == "green" -> Color(0xFF008000)
        value == "blue" -> Color(0xFF0000FF)
        else -> null
    }
}

private fun parseHexColor(hex: String): Color? {
    val digits = hex.filter { it.isLetterOrDigit() }
    return when (digits.length) {
        3 -> Color(
            red = digits[0].digit() * 17,
            green = digits[1].digit() * 17,
            blue = digits[2].digit() * 17,
        )
        4 -> Color(
            red = digits[0].digit() * 17,
            green = digits[1].digit() * 17,
            blue = digits[2].digit() * 17,
            alpha = digits[3].digit() * 17,
        )
        6 -> Color(
            red = digits.substring(0, 2).toIntOrNull(16) ?: return null,
            green = digits.substring(2, 4).toIntOrNull(16) ?: return null,
            blue = digits.substring(4, 6).toIntOrNull(16) ?: return null,
        )
        8 -> Color(
            red = digits.substring(0, 2).toIntOrNull(16) ?: return null,
            green = digits.substring(2, 4).toIntOrNull(16) ?: return null,
            blue = digits.substring(4, 6).toIntOrNull(16) ?: return null,
            alpha = digits.substring(6, 8).toIntOrNull(16) ?: return null,
        )
        else -> null
    }
}

private fun Char.digit(): Int = digitToIntOrNull(16) ?: 0

private fun parseRgbColor(value: String): Color? {
    val parts = value.substringAfter('(', "").substringBefore(')').split(',')
    if (parts.size < 3) return null
    val red = parts[0].colorComponent() ?: return null
    val green = parts[1].colorComponent() ?: return null
    val blue = parts[2].colorComponent() ?: return null
    val alpha = parts.getOrNull(3)?.alphaComponent() ?: 1f
    return Color(
        red = red.roundToInt().coerceIn(0, 255),
        green = green.roundToInt().coerceIn(0, 255),
        blue = blue.roundToInt().coerceIn(0, 255),
        alpha = (alpha * 255).roundToInt().coerceIn(0, 255),
    )
}

private fun String.colorComponent(): Float? {
    val part = trim()
    return if (part.endsWith("%")) {
        part.dropLast(1).toFloatOrNull()?.times(2.55f)
    } else {
        part.toFloatOrNull()
    }
}

private fun String.alphaComponent(): Float? {
    val part = trim()
    return if (part.endsWith("%")) {
        part.dropLast(1).toFloatOrNull()?.div(100f)
    } else {
        part.toFloatOrNull()?.takeIf { it > 1f }?.div(255f) ?: part.toFloatOrNull()
    }
}

private fun parseHslColor(value: String): Color? {
    val parts = value.substringAfter('(', "").substringBefore(')').split(',')
    if (parts.size < 3) return null
    val hue = parts[0].trim().removeSuffix("deg").toFloatOrNull() ?: return null
    val saturation = (parts[1].trim().removeSuffix("%").toFloatOrNull() ?: return null) / 100f
    val lightness = (parts[2].trim().removeSuffix("%").toFloatOrNull() ?: return null) / 100f
    val alpha = parts.getOrNull(3)?.alphaComponent() ?: 1f

    val (red, green, blue) = hslToRgb(hue, saturation, lightness)
    return Color(
        red = (red * 255).roundToInt().coerceIn(0, 255),
        green = (green * 255).roundToInt().coerceIn(0, 255),
        blue = (blue * 255).roundToInt().coerceIn(0, 255),
        alpha = (alpha * 255).roundToInt().coerceIn(0, 255),
    )
}

private fun hslToRgb(hue: Float, saturation: Float, lightness: Float): Triple<Float, Float, Float> {
    if (saturation == 0f) {
        return Triple(lightness, lightness, lightness)
    }
    val normalizedHue = ((hue % 360) + 360) % 360 / 360f
    val chroma = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
    val second = chroma * (1 - kotlin.math.abs(normalizedHue * 6 % 2 - 1))
    val (r, g, b) = when {
        normalizedHue < 1 / 6f -> Triple(chroma, second, 0f)
        normalizedHue < 2 / 6f -> Triple(second, chroma, 0f)
        normalizedHue < 3 / 6f -> Triple(0f, chroma, second)
        normalizedHue < 4 / 6f -> Triple(0f, second, chroma)
        normalizedHue < 5 / 6f -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    val match = lightness - chroma / 2
    return Triple(r + match, g + match, b + match)
}

private fun isDarkColor(color: Color): Boolean = color.relativeLuminance() < 0.5f

private fun autoTextColor(background: Color): Color =
    if (background.relativeLuminance() > 0.55f) Color(0xFF1A1A1A) else Color(0xFFFFFFFF)

private fun Color.relativeLuminance(): Float {
    fun channel(component: Float): Float =
        if (component <= 0.03928f) {
            component / 12.92f
        } else {
            ((component + 0.045f) / 1.055f).pow(2.4f)
        }
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

private fun blend(start: Color, end: Color, fraction: Float): Color = Color(
    red = start.red + (end.red - start.red) * fraction,
    green = start.green + (end.green - start.green) * fraction,
    blue = start.blue + (end.blue - start.blue) * fraction,
    alpha = start.alpha + (end.alpha - start.alpha) * fraction,
)

private fun lighten(color: Color, amount: Float): Color =
    blend(color, Color(0xFFFFFFFF), amount.coerceIn(0f, 1f))

private fun darken(color: Color, amount: Float): Color =
    blend(color, Color(0xFF000000), amount.coerceIn(0f, 1f))
