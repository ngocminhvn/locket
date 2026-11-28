package com.locket.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.locket.app.model.Moment
import com.locket.app.model.CaptionOverlays
import com.locket.app.model.CaptionSymbol
import com.locket.app.model.captionSymbol
import kotlin.math.cos
import kotlin.math.sin

internal object WidgetCaptionRenderer {
    fun render(context: Context, moment: Moment, maxWidth: Float, widgetScale: Float): Bitmap? {
        val data = moment.overlay
        val type = data?.kind.orEmpty()
        val rating = data?.rating ?: 0f
        val review = type == "review" && rating > 0f
        val comment = data?.comment.orEmpty()
        val symbol = data?.iconData.orEmpty()
        val prefix = when (captionSymbol(type, data?.iconKind.orEmpty(), symbol)) {
            CaptionSymbol.LOCATION -> "📍"
            CaptionSymbol.CLOCK -> "◷"
            CaptionSymbol.FLAME -> "🔥"
            CaptionSymbol.SUN -> "☀"
            CaptionSymbol.MOON -> "☾"
            CaptionSymbol.PARTLY_CLOUDY -> "🌤"
            CaptionSymbol.SNOW -> "❄"
            CaptionSymbol.RAIN -> "☂"
            CaptionSymbol.THUNDER -> "⛈"
            CaptionSymbol.CLOUD -> "☁"
            CaptionSymbol.WIND -> "💨"
            CaptionSymbol.FOG -> "🌫"
            CaptionSymbol.SLEET -> "🌨"
            null -> if (data?.iconKind == "emoji") symbol else ""
        }
        val label = if (review) comment else listOf(prefix, data?.text.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { CaptionOverlays.label(moment) }
        if (!review && label.isBlank()) return null
        val scale = context.resources.displayMetrics.density * widgetScale
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            this.color = 0xFFFFFFFF.toInt()
        }
        val limit = (maxWidth / widgetScale).coerceAtLeast(12f)
        val clipped = TextUtils.ellipsize(label, paint, (limit - 16f).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
        val width = (if (review) maxOf(76f, paint.measureText(clipped) + 16f) else paint.measureText(clipped) + 16f).coerceIn(12f, limit)
        val height = if (review && comment.isNotBlank()) 38f else 24f
        val bitmap = Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
        canvas.drawRoundRect(0f, 0f, width, height, height / 2f, height / 2f, bg)
        paint.textAlign = Paint.Align.CENTER
        if (review) {
            val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFFFFC107.toInt()
                strokeWidth = 1.2f
                strokeJoin = Paint.Join.ROUND
            }
            repeat(5) { i ->
                val spacing = minOf(12f, (width - 8f) / 5f)
                val starScale = minOf(1f, spacing / 19f)
                val cx = width / 2f + (i - 2) * spacing
                val cy = if (comment.isBlank()) height / 2f else 10f
                val path = Path()
                repeat(10) { j ->
                    val angle = -Math.PI / 2 + j * Math.PI / 5
                    val radius = (if (j % 2 == 0) 7f else 3.4f) * starScale
                    val x = cx + (cos(angle) * radius).toFloat()
                    val y = cy + (sin(angle) * radius).toFloat()
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val filled = (rating - i).coerceIn(0f, 1f)
                starPaint.style = Paint.Style.FILL
                starPaint.alpha = 97
                canvas.drawPath(path, starPaint)
                starPaint.alpha = 255
                if (filled > 0f) {
                    val save = canvas.save()
                    canvas.clipRect(cx - 8f * starScale, cy - 8f * starScale, cx - 8f * starScale + 16f * starScale * filled, cy + 8f * starScale)
                    starPaint.style = Paint.Style.FILL
                    canvas.drawPath(path, starPaint)
                    canvas.restoreToCount(save)
                }
            }
            if (comment.isNotBlank()) canvas.drawText(clipped, width / 2f, 28f, paint)
        } else {
            canvas.drawText(clipped, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        return bitmap
    }
}
