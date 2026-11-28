package com.locket.app.ui.screens

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.locket.app.ui.theme.LocketBlack
import com.locket.app.ui.theme.extractMomentColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AmbientPalette(val top: Color, val middle: Color, val bottom: Color) {
    fun brush(): Brush = Brush.verticalGradient(0f to top, 0.48f to middle, 1f to bottom)
}
private val neutral = AmbientPalette(LocketBlack, Color(0xFF19191B), LocketBlack)

/** Each screen shares the extracted palette but chooses its own darkness. */
internal enum class AmbientMode(val strength: Float, val durationMs: Int) {
    HOME(1f, 280), HISTORY(0.74f, 220), MEMORY(1f, 280), CONVERSATION(0.68f, 300), ACTIVITY(0.74f, 280)
}
private val paletteCache = LruCache<String, AmbientPalette>(128)

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
            val next = paletteCache.get(url) ?: withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(context).data(url)
                        .size(64).allowHardware(false).crossfade(false).build()
                    val result = context.imageLoader.execute(request) as? SuccessResult
                    result?.drawable?.toBitmap(64, 64, Bitmap.Config.ARGB_8888)
                        ?.let(::makeAmbientPalette)?.also { paletteCache.put(url, it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (next != null) palette = next
        }
    }
    fun tone(color: Color): Color = Color(
        red = LocketBlack.red + (color.red - LocketBlack.red) * mode.strength,
        green = LocketBlack.green + (color.green - LocketBlack.green) * mode.strength,
        blue = LocketBlack.blue + (color.blue - LocketBlack.blue) * mode.strength
    )
    val top by animateColorAsState(tone(palette.top), tween(mode.durationMs, easing = FastOutSlowInEasing), label = "ambient-top")
    val middle by animateColorAsState(tone(palette.middle), tween(mode.durationMs, easing = FastOutSlowInEasing), label = "ambient-middle")
    val bottom by animateColorAsState(tone(palette.bottom), tween(mode.durationMs, easing = FastOutSlowInEasing), label = "ambient-bottom")
    return AmbientPalette(top, middle, bottom)
}

@Composable
internal fun AmbientBackground(url: String?, mode: AmbientMode) {
    val palette = rememberAmbientPalette(url, mode)
    Box(Modifier.fillMaxSize().background(palette.brush()))
}

private fun makeAmbientPalette(bitmap: Bitmap): AmbientPalette {
    // Overlap thirds to reduce abrupt hue changes and to keep the actual
    // spatial distribution of the photograph. The shared extractor is
    // unchanged; only the Home colors receive the following tone mapping.
    val global = extractMomentColor(bitmap)
    fun band(start: Int, end: Int, edge: Boolean): Color {
        val from = start * bitmap.height / 100
        val until = (end * bitmap.height / 100).coerceAtLeast(from + 1)
        val crop = Bitmap.createBitmap(bitmap, 0, from, bitmap.width, until - from)
        val local = try { extractMomentColor(crop) } finally {
            if (crop !== bitmap) crop.recycle()
        }
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
    return AmbientPalette(band(0, 45, true), band(25, 75, false), band(55, 100, true))
}
