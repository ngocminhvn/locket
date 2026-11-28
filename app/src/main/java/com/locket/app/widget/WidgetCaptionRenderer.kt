package com.locket.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.locket.app.model.CaptionOverlays
import com.locket.app.model.Moment
import kotlin.math.cos
import kotlin.math.sin

internal object WidgetCaptionRenderer {
    private fun color(raw: String, fallback: Int): Int = runCatching {
        val s = raw.removePrefix("#")
        when (s.length) {
            6 -> Color.parseColor("#" + s)
            8 -> Color.parseColor("#" + s.takeLast(2) + s.take(6))
            else -> fallback
        }
    }.getOrDefault(fallback)

    fun render(context: Context, moment: Moment): Bitmap? {
        val o = CaptionOverlays
        val data = o.data(moment) ?: return null
        val type = o.string(data, "type")
        val payload = o.obj(data, "payload")
        val rating = runCatching { payload?.get("rating")?.asFloat ?: 0f }.getOrDefault(0f)
        val review = type == "review" && rating.isFinite() && rating > 0f
        val comment = o.string(payload, "comment")
        val icon = o.obj(data, "icon")
        val symbol = o.string(icon, "data")
        val prefix = when (o.string(icon, "type")) {
            "emoji" -> symbol
            "sf_symbol" -> when {
                symbol.startsWith("clock") -> "◷"
                symbol.startsWith("flame") -> "🔥"
                symbol.startsWith("sun.max") -> "☀"
                symbol.startsWith("moon") || symbol.startsWith("cloud.moon") -> "☾"
                symbol.startsWith("snow") -> "❄"
                symbol.startsWith("cloud.rain") -> "☂"
                symbol.startsWith("cloud") -> "☁"
                else -> ""
            }
            else -> if (type == "location") "📍" else ""
        }
        val label = if (review) comment else listOf(prefix, o.string(data, "text")).filter { it.isNotBlank() }.joinToString(" ")
        if (!review && label.isBlank()) return null
        val scale = context.resources.displayMetrics.density.coerceIn(1f, 3f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            this.color = color(o.string(data, "text_color"), Color.WHITE)
        }
        val clipped = TextUtils.ellipsize(label, paint, 224f, TextUtils.TruncateAt.END).toString()
        val width = (if (review) maxOf(112f, paint.measureText(clipped) + 20f) else paint.measureText(clipped) + 20f).coerceIn(32f, 244f)
        val height = if (review && comment.isNotBlank()) 46f else 30f
        val bitmap = Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val colors = runCatching {
            o.obj(data, "background")?.getAsJsonArray("colors")?.map { color(it.asString, 0x99000000.toInt()) }.orEmpty()
        }.getOrDefault(emptyList())
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = colors.firstOrNull() ?: 0x99000000.toInt()
            if (colors.size >= 2) shader = LinearGradient(0f, 0f, 0f, height, colors.toIntArray(), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(0f, 0f, width, height, 14f, 14f, bg)
        paint.textAlign = Paint.Align.CENTER
        if (review) {
            val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFFFDD835.toInt()
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
                if (filled > 0f) {
                    val save = canvas.save()
                    canvas.clipRect(cx - 8f, cy - 8f, cx - 8f + 16f * filled, cy + 8f)
                    starPaint.style = Paint.Style.FILL
                    canvas.drawPath(path, starPaint)
                    canvas.restoreToCount(save)
                }
                starPaint.style = Paint.Style.STROKE
                canvas.drawPath(path, starPaint)
            }
            if (comment.isNotBlank()) canvas.drawText(clipped, width / 2f, 37f, paint)
        } else {
            canvas.drawText(clipped, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        return bitmap
    }
}
