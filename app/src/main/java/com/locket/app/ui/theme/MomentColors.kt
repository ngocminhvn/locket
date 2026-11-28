package com.locket.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

internal val BackdropFallbackTint = Color(0xFF3D3D3D)
private val BackdropScrim = Color(0xFF0A090B)
internal const val HISTORY_SCRIM = 0.76f
internal const val CONVERSATION_SCRIM = 0.68f
private val colorCache = LruCache<String, Color>(128)
private val backdropEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

@Composable
internal fun rememberMomentColor(url: String?, durationMs: Int = 220): Color {
    val context = LocalContext.current.applicationContext
    var target by remember { mutableStateOf(BackdropFallbackTint) }
    LaunchedEffect(context, url) {
        if (url.isNullOrBlank()) {
            target = BackdropFallbackTint
        } else {
            val next = colorCache.get(url) ?: withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(context).data(url)
                        .size(64).allowHardware(false).crossfade(false).build()
                    val result = context.imageLoader.execute(request) as? SuccessResult
                    result?.drawable?.toBitmap(64, 64, Bitmap.Config.ARGB_8888)
                        ?.let(::extractMomentColor)?.also { colorCache.put(url, it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            // Failed reads retain the last successful tint; retry on revisit.
            if (next != null) target = next
        }
    }
    val animated by animateColorAsState(target, tween(durationMs, easing = backdropEasing), label = "moment-backdrop-tint")
    return animated
}

/** Local approximation of native BackdropColor, whose exact algorithm is
 * unknown. Extract an un-darkened representative chromatic color from the
 * whole thumbnail. Neutral photos fall back to their average pixel color.
 */
internal fun extractMomentColor(bitmap: Bitmap): Color {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val bins = 24
    val weights = DoubleArray(bins)
    val reds = DoubleArray(bins)
    val greens = DoubleArray(bins)
    val blues = DoubleArray(bins)
    val hsv = FloatArray(3)
    var count = 0
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    pixels.forEach { pixel ->
        if (AndroidColor.alpha(pixel) < 128) return@forEach
        val r = AndroidColor.red(pixel)
        val g = AndroidColor.green(pixel)
        val b = AndroidColor.blue(pixel)
        count++
        sumR += r; sumG += g; sumB += b
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val chroma = (max - min) / 255.0
        if (max < 38 || chroma < 0.025) return@forEach
        AndroidColor.colorToHSV(pixel, hsv)
        val bin = (hsv[0] / 15f).toInt().coerceIn(0, bins - 1)
        val w = chroma * sqrt(chroma)
        weights[bin] += w
        reds[bin] += r * w
        greens[bin] += g * w
        blues[bin] += b * w
    }
    if (count == 0) return BackdropFallbackTint
    if (weights.sum() / count < 0.003) {
        return Color((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
    }
    fun neighborhood(values: DoubleArray, bin: Int): Double =
        values[bin] + 0.65 * (values[(bin + bins - 1) % bins] + values[(bin + 1) % bins])
    val best = (0 until bins).maxByOrNull { neighborhood(weights, it) } ?: return BackdropFallbackTint
    val weight = neighborhood(weights, best)
    if (weight <= 0.0) return BackdropFallbackTint
    return Color(
        (neighborhood(reds, best) / weight).toInt().coerceIn(0, 255),
        (neighborhood(greens, best) / weight).toInt().coerceIn(0, 255),
        (neighborhood(blues, best) / weight).toInt().coerceIn(0, 255)
    )
}

/** The original JS interpolates RGB with gamma=1, not perceptual color space. */
internal fun backdropColor(tint: Color, scrim: Float): Color = Color(
    red = tint.red * (1f - scrim) + BackdropScrim.red * scrim,
    green = tint.green * (1f - scrim) + BackdropScrim.green * scrim,
    blue = tint.blue * (1f - scrim) + BackdropScrim.blue * scrim
)
