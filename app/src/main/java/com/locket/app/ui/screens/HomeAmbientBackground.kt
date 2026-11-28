package com.locket.app.ui.screens

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.locket.app.ui.theme.LocketBlack
import com.locket.app.ui.theme.sampleMomentColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AmbientPalette(val top: Color, val middle: Color, val bottom: Color) {
    fun brush(): Brush = Brush.verticalGradient(0f to top, 0.48f to middle, 1f to bottom)
}
private val neutral = AmbientPalette(LocketBlack, Color(0xFF19191B), LocketBlack)

private val ambientCubicOut = Easing { t ->
    val remaining = 1f - t
    1f - remaining * remaining * remaining
}
private val ambientQuadOut = Easing { t ->
    val remaining = 1f - t
    1f - remaining * remaining
}

/** Reference-derived timing; keep Locket's custom three-tone tint architecture. */
internal enum class AmbientMode(val strength: Float, val durationMs: Int, val easing: Easing) {
    // Home has no equivalent tint transition in the original; retain our custom timing.
    HOME(1f, 160, FastOutSlowInEasing),
    HISTORY(0.74f, 150, ambientCubicOut),
    MEMORY(1f, 150, ambientCubicOut),
    CONVERSATION(0.68f, 300, ambientQuadOut),
    ACTIVITY(0.74f, 220, ambientQuadOut)
}
private val paletteCache = LruCache<String, AmbientPalette>(128)
private val paletteLoad = Mutex()

private suspend fun loadAmbientPalette(context: android.content.Context, url: String, cacheOnly: Boolean): AmbientPalette? =
    paletteCache.get(url) ?: paletteLoad.withLock {
        paletteCache.get(url) ?: try {
            val result = withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context).data(url)
                    .size(64).allowHardware(false).crossfade(false)
                    .networkCachePolicy(if (cacheOnly) CachePolicy.DISABLED else CachePolicy.ENABLED)
                    .build()
                context.imageLoader.execute(request) as? SuccessResult
            }
            withContext(Dispatchers.Default) {
                result?.drawable?.toBitmap(64, 64, Bitmap.Config.ARGB_8888)?.let(::makeAmbientPalette)
            }?.also { paletteCache.put(url, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

internal suspend fun prefetchAmbientPalettes(context: android.content.Context, urls: List<String>) {
    for (url in urls.filter { it.isNotBlank() }.distinct().take(2)) {
        loadAmbientPalette(context, url, cacheOnly = true)
    }
}

/**
 * Shared three-tone ambient gradient. Sample overlapping horizontal regions
 * of a thumbnail so the gradient follows the photo rather than a fixed theme.
 * Only three colors are rendered: no enlarged photo, no full-screen blur.
 */
@Composable
internal fun rememberAmbientPalette(url: String?, mode: AmbientMode): AmbientPalette {
    val context = LocalContext.current.applicationContext
    var palette by remember { mutableStateOf(url?.let { paletteCache.get(it) } ?: neutral) }
    LaunchedEffect(context, url) {
        if (url.isNullOrBlank()) {
            palette = neutral
        } else {
            val next = loadAmbientPalette(context, url, cacheOnly = false)
            if (next != null) palette = next
        }
    }
    fun tone(color: Color): Color = Color(
        red = LocketBlack.red + (color.red - LocketBlack.red) * mode.strength,
        green = LocketBlack.green + (color.green - LocketBlack.green) * mode.strength,
        blue = LocketBlack.blue + (color.blue - LocketBlack.blue) * mode.strength
    )
    val top by animateColorAsState(tone(palette.top), tween(mode.durationMs, easing = mode.easing), label = "ambient-top")
    val middle by animateColorAsState(tone(palette.middle), tween(mode.durationMs, easing = mode.easing), label = "ambient-middle")
    val bottom by animateColorAsState(tone(palette.bottom), tween(mode.durationMs, easing = mode.easing), label = "ambient-bottom")
    return AmbientPalette(top, middle, bottom)
}

@Composable
internal fun AmbientBackground(url: String?, mode: AmbientMode) {
    val palette = rememberAmbientPalette(url, mode)
    Box(Modifier.fillMaxSize().background(palette.brush()))
}

private fun makeAmbientPalette(bitmap: Bitmap): AmbientPalette {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    fun rows(start: Int, end: Int): IntRange {
        val from = start * height / 100
        val endExclusive = (end * height / 100).coerceAtLeast(from + 1)
        return from until endExclusive
    }
    val (global, top, middle, bottom) = sampleMomentColors(
        pixels, width, height, listOf(0 until height, rows(0, 45), rows(25, 75), rows(55, 100))
    )
    fun band(local: Color, edge: Boolean): Color {
        val hsl = FloatArray(3)
        fun channel(a: Float, b: Float) = ((a * 0.78f + b * 0.22f) * 255f).toInt().coerceIn(0, 255)
        ColorUtils.RGBToHSL(channel(local.red, global.red), channel(local.green, global.green),
            channel(local.blue, global.blue), hsl)
        // Preserve hue, tame very bright/saturated photos and keep white
        // navigation/text readable. Edges are slightly darker than center.
        val saturation = hsl[1]
        val lightness = hsl[2]
        hsl[1] = (saturation * 0.78f).coerceAtMost(0.55f)
        hsl[2] = (if (edge) 0.075f + 0.09f * lightness
                  else 0.105f + 0.12f * lightness).coerceIn(0.075f, 0.225f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
    return AmbientPalette(band(top, true), band(middle, false), band(bottom, true))
}
