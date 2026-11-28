package com.locket.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.locket.app.model.Moment
import com.locket.app.model.CaptionSymbol
import com.locket.app.model.captionSymbol
import kotlin.math.cos
import kotlin.math.sin

internal object WidgetCaptionRenderer {
    fun render(context: Context, moment: Moment, maxWidth: Float = 244f, compact: Boolean = false): Bitmap? {
        val data = moment.overlay ?: return null
        val type = data.kind
        val rating = data.rating
        val review = type == "review" && rating > 0f
        val comment = data.comment
        val symbol = data.iconData
        val prefix = when (captionSymbol(type, data.iconKind, symbol)) {
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
            null -> if (data.iconKind == "emoji") symbol else ""
        }
        val label = if (review) comment else listOf(prefix, data.text).filter { it.isNotBlank() }.joinToString(" ")
        if (!review && label.isBlank()) return null
        val scale = context.resources.displayMetrics.density.coerceIn(1f, 3f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = if (compact) 12f else 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            this.color = data.foreground
        }
        val limit = maxWidth.coerceIn(32f, 320f)
        val clipped = TextUtils.ellipsize(label, paint, (limit - 20f).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
        val width = (if (review) maxOf(if (compact) 70f else 112f, paint.measureText(clipped) + 20f) else paint.measureText(clipped) + 20f).coerceIn(32f, limit)
        val height = if (review && comment.isNotBlank()) 46f else if (compact) 24f else 30f
        val bitmap = Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val colors = data.colors
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = colors.firstOrNull() ?: 0x99000000.toInt()
            if (colors.size >= 2) shader = LinearGradient(0f, 0f, 0f, height, colors.toIntArray(), null, Shader.TileMode.CLAMP)
        }
        if (!(compact && review)) canvas.drawRoundRect(0f, 0f, width, height, height / 2f, height / 2f, bg)
        paint.textAlign = Paint.Align.CENTER
        if (review) {
            val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFFFFC107.toInt()
                strokeWidth = 1.2f
                strokeJoin = Paint.Join.ROUND
            }
            repeat(5) { i ->
                val spacing = minOf(if (compact) 12f else 19f, width / 5f)
                val starScale = minOf(1f, spacing / 19f)
                val cx = width / 2f + (i - 2) * spacing
                val cy = if (compact && comment.isBlank()) height / 2f else 15f
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
            if (comment.isNotBlank()) canvas.drawText(clipped, width / 2f, 37f, paint)
        } else {
            canvas.drawText(clipped, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        return bitmap
    }
}
