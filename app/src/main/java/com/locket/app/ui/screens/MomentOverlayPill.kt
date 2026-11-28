package com.locket.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.locket.app.model.CaptionOverlays
import com.locket.app.model.Moment
import coil.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun OverlayReviewStar(fill: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val tint = Color(0xFFFDD835)
        val radius = size.minDimension * 0.43f
        val star = Path().apply {
            repeat(10) { i ->
                val angle = -Math.PI / 2 + i * Math.PI / 5
                val r = radius * if (i % 2 == 0) 1f else 0.48f
                val x = center.x + (cos(angle) * r).toFloat()
                val y = center.y + (sin(angle) * r).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        clipRect(right = size.width * fill.coerceIn(0f, 1f)) { drawPath(star, tint) }
        drawPath(star, tint, style = Stroke(width = size.minDimension * 0.08f, join = StrokeJoin.Round))
    }
}

// Wire colors are CSS RRGGBBAA, not Android AARRGGBB.
private fun overlayColor(raw: String, fallback: Color): Color = runCatching {
    val s = raw.removePrefix("#")
    when (s.length) {
        6 -> Color((0xFF000000L or s.toLong(16)).toInt())
        8 -> Color(((s.takeLast(2) + s.take(6)).toLong(16)).toInt())
        else -> fallback
    }
}.getOrDefault(fallback)

@Composable
internal fun MomentCaption(moment: Moment, modifier: Modifier = Modifier, fallback: String = moment.caption) {
    val data = CaptionOverlays.data(moment)
    val spotifyUrl = if (CaptionOverlays.string(data, "type") == "music")
        SpotifyCaptionSource.canonicalTrack(CaptionOverlays.string(CaptionOverlays.obj(data, "payload"), "spotify_url")) else null
    val context = androidx.compose.ui.platform.LocalContext.current
    val action = if (spotifyUrl != null) Modifier.clickable {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(spotifyUrl))) }
            .onFailure { android.widget.Toast.makeText(context, "Không mở được Spotify", android.widget.Toast.LENGTH_SHORT).show() }
    } else Modifier
    if (data != null || fallback.isNotBlank()) MomentOverlayPill(data, fallback, modifier.then(action))
}

@Composable
internal fun MomentOverlayPill(data: JsonObject?, fallback: String, modifier: Modifier = Modifier) {
    val o = CaptionOverlays
    val type = o.string(data, "type")
    val label = o.string(data, "text").ifBlank { fallback }
    val payload = o.obj(data, "payload")
    val background = o.obj(data, "background")
    val colors = runCatching {
        background?.getAsJsonArray("colors")?.map { overlayColor(it.asString, Color(0x99000000)) }.orEmpty()
    }.getOrDefault(emptyList())
    val foreground = overlayColor(o.string(data, "text_color"), Color.White)
    val shape = RoundedCornerShape(20.dp)
    val review = type == "review"
    val rating = runCatching { payload?.get("rating")?.asFloat ?: 0f }.getOrDefault(0f).coerceIn(0f, 5f)
    if (label.isBlank() && !review) return
    val surface = modifier.clip(shape).then(
        if (colors.size >= 2) Modifier.background(Brush.verticalGradient(colors))
        else Modifier.background(colors.firstOrNull() ?: Color.Black.copy(alpha = if (review) 0.60f else 0.40f))
    )
    if (review && rating > 0f) {
        Column(surface.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(5) { i -> OverlayReviewStar((rating - i).coerceIn(0f, 1f), Modifier.size(21.dp)) }
            }
            val comment = o.string(payload, "comment")
            if (comment.isNotBlank()) Text("“$comment”", color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    Row(surface.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val icon = o.obj(data, "icon")
        val name = o.string(icon, "data")
        val iconColor = overlayColor(o.string(icon, "color"), foreground)
        when (o.string(icon, "type")) {
            "emoji" -> if (name.isNotBlank()) Text(name, fontSize = 18.sp)
            "image" -> if (name.startsWith("https://")) AsyncImage(name, null, Modifier.size(22.dp))
            "sf_symbol" -> {
                val vector = when {
                    name.startsWith("clock") -> Icons.Default.AccessTime
                    name.startsWith("flame") -> Icons.Default.LocalFireDepartment
                    name.startsWith("sun.max") -> Icons.Default.WbSunny
                    name.startsWith("moon") -> Icons.Default.NightsStay
                    name.startsWith("cloud.sun") -> Icons.Default.WbCloudy
                    name.startsWith("cloud.moon") -> Icons.Default.NightsStay
                    name.startsWith("snow") -> Icons.Default.AcUnit
                    name.startsWith("cloud.rain") -> Icons.Default.Grain
                    name.startsWith("cloud.bolt") -> Icons.Default.Thunderstorm
                    else -> Icons.Default.Cloud
                }
                Icon(vector, null, modifier = Modifier.size(22.dp), tint = if (name.startsWith("sun.max")) Color(0xFFFFC107) else iconColor)
            }
            else -> when (type) {
                "location" -> Icon(Icons.Default.LocationOn, null, tint = iconColor, modifier = Modifier.size(20.dp))
                "music" -> Icon(Icons.Default.MusicNote, null, tint = Color(0xFF1ED760), modifier = Modifier.size(20.dp))
            }
        }
        Text(label, color = foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            maxLines = runCatching { data?.get("max_lines")?.asInt ?: 4 }.getOrDefault(4).coerceIn(1, 4),
            overflow = TextOverflow.Ellipsis)
    }
}
