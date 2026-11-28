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
    fun render(context: Context, moment: Moment): Bitmap? {
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
            null -> if (data.iconKind == "emoji") symbol else ""
        }
        val label = if (review) comment else listOf(prefix, data.text).filter { it.isNotBlank() }.joinToString(" ")
        if (!review && label.isBlank()) return null
        val scale = context.resources.displayMetrics.density.coerceIn(1f, 3f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            this.color = data.foreground
        }
        val clipped = TextUtils.ellipsize(label, paint, 224f, TextUtils.TruncateAt.END).toString()
        val width = (if (review) maxOf(112f, paint.measureText(clipped) + 20f) else paint.measureText(clipped) + 20f).coerceIn(32f, 244f)
        val height = if (review && comment.isNotBlank()) 46f else 30f
        val bitmap = Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val colors = data.colors
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = colors.firstOrNull() ?: 0x99000000.toInt()
            if (colors.size >= 2) shader = LinearGradient(0f, 0f, 0f, height, colors.toIntArray(), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(0f, 0f, width, height, 14f, 14f, bg)
        paint.textAlign = Paint.Align.CENTER
        if (review) {
            val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFFFFC107.toInt()
                strokeWidth = 1.2f
                strokeJoin = Paint.Join.ROUND
            }
            repeat(5) { i ->
                val cx = width / 2f + (i - 2) * 19f
                val cy = 15f
                val path = Path()
                repeat(10) { j ->
                    val angle = -Math.PI / 2 + j * Math.PI / 5
                    val radius = if (j % 2 == 0) 7f else 3.4f
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
                    canvas.clipRect(cx - 8f, cy - 8f, cx - 8f + 16f * filled, cy + 8f)
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
