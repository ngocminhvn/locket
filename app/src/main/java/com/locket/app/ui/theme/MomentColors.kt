package com.locket.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** The last settled feed image drives the shared ambient chrome. Null = neutral. */
internal val LocalMomentColor = compositionLocalOf<Color?> { null }
private val colorCache = LruCache<String, Color>(128)

@Composable
internal fun rememberMomentColor(url: String?): Color? {
    val context = LocalContext.current.applicationContext
    var target by remember { mutableStateOf(url?.let { colorCache.get(it) }) }
    LaunchedEffect(context, url) {
        if (url.isNullOrBlank()) {
            target = null
        } else {
            // Retain the previous tone while the new thumbnail is decoded. A
            // cancelled effect cannot publish a stale result after a newer page.
            target = colorCache.get(url) ?: withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context).data(url)
                    .size(64).allowHardware(false).crossfade(false).build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                result?.drawable?.toBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    ?.let(::extractMomentColor)?.also { colorCache.put(url, it) }
            }
        }
    }
    val animated by animateColorAsState(target ?: LocketBlack, tween(280), label = "moment-ambient-color")
    return if (target == null) null else animated
}

/** Weighted circular hue histogram; ignore near-neutral pixels, favor coherent
 *  chromatic regions, and moderately favor the upper part of the photograph.
 *  This is an approximation calibrated against original-app screenshots, not
 *  a hardcoded list of photo colors or a claim about the native algorithm.
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
    pixels.forEachIndexed { i, pixel ->
        if (AndroidColor.alpha(pixel) < 128) return@forEachIndexed
        val r = AndroidColor.red(pixel)
        val g = AndroidColor.green(pixel)
        val b = AndroidColor.blue(pixel)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val chroma = (max - min) / 255.0
        if (max < 38 || chroma < 0.025) return@forEachIndexed
        AndroidColor.colorToHSV(pixel, hsv)
        val bin = (hsv[0] / 15f).toInt().coerceIn(0, bins - 1)
        val top = 1.0 - (i / width).toDouble() / (height - 1).coerceAtLeast(1)
        val w = chroma * sqrt(chroma) * (1.0 + 2.0 * top * top * top)
        weights[bin] += w
        reds[bin] += r * w
        greens[bin] += g * w
        blues[bin] += b * w
    }
    if (weights.sum() / pixels.size < 0.003) return Color(0xFF1B1B1B)
    fun neighborhood(values: DoubleArray, bin: Int): Double =
        values[bin] + 0.65 * (values[(bin + bins - 1) % bins] + values[(bin + 1) % bins])
    val best = (0 until bins).maxByOrNull { neighborhood(weights, it) } ?: return LocketBlack
    val weight = neighborhood(weights, best)
    if (weight <= 0.0) return LocketBlack
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(
        (neighborhood(reds, best) / weight).toInt().coerceIn(0, 255),
        (neighborhood(greens, best) / weight).toInt().coerceIn(0, 255),
        (neighborhood(blues, best) / weight).toInt().coerceIn(0, 255),
        hsl
    )
    val sourceLightness = hsl[2]
    val sourceSaturation = hsl[1]
    val brightSoftening = 1f - ((sourceLightness - 0.55f).coerceAtLeast(0f) * 2.5f).coerceAtMost(0.55f)
    hsl[1] = (sourceSaturation * (1.1f - 0.65f * sourceLightness) * brightSoftening).coerceIn(0f, 0.58f)
    hsl[2] = (0.09f + 0.16f * sourceLightness - 0.045f * sourceSaturation).coerceIn(0.10f, 0.20f)
    return Color(ColorUtils.HSLToColor(hsl))
}

internal fun momentEdge(base: Color?): Color =
    base?.let { lerp(it, Color.Black, 0.17f) } ?: LocketBlack

internal fun momentGradient(base: Color?): Brush = Brush.verticalGradient(
    0f to momentEdge(base),
    0.22f to (base ?: LocketBlack),
    0.77f to (base ?: LocketBlack),
    1f to momentEdge(base)
)

internal fun momentSurface(base: Color?, fallback: Color, amount: Float): Color =
    base?.let { lerp(it, Color.White, amount) } ?: fallback
