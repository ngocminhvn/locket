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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import com.locket.app.model.CaptionOverlay
import com.locket.app.model.CaptionSymbol
import com.locket.app.model.captionSymbol
import com.locket.app.model.Moment
import com.locket.app.ui.components.SkeletonAsyncImage
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun OverlayReviewStar(fill: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val tint = Color(0xFFFFC107)
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
        drawPath(star, tint.copy(alpha = 0.38f))
        if (fill > 0f) clipRect(right = size.width * fill.coerceIn(0f, 1f)) { drawPath(star, tint) }
    }
}

@Composable
internal fun MomentCaption(moment: Moment, modifier: Modifier = Modifier, fallback: String = moment.caption) {
    val data = moment.overlay
    val spotifyUrl = remember(data) {
        data?.takeIf { it.kind == "music" }?.let { SpotifyCaptionSource.canonicalTrack(it.spotifyUrl) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val action = if (spotifyUrl != null) Modifier.clickable {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(spotifyUrl))) }
            .onFailure { android.widget.Toast.makeText(context, "Không mở được Spotify", android.widget.Toast.LENGTH_SHORT).show() }
    } else Modifier
    if (data != null || fallback.isNotBlank()) MomentOverlayPill(data, fallback, modifier.then(action))
}

@Composable
internal fun MomentOverlayPill(data: CaptionOverlay?, fallback: String, modifier: Modifier = Modifier) {
    val type = data?.kind
    val label = data?.text.orEmpty().ifBlank { fallback }
    val colors = remember(data) { data?.colors?.map { Color(it) }.orEmpty() }
    val foreground = data?.let { Color(it.foreground) } ?: Color.White
    val shape = RoundedCornerShape(20.dp)
    val review = type == "review"
    val rating = data?.rating ?: 0f
    if (label.isBlank() && !review) return
    val surface = modifier.clip(shape).then(
        if (colors.size >= 2) Modifier.background(Brush.verticalGradient(colors))
        else Modifier.background(colors.firstOrNull() ?: Color.Black.copy(alpha = if (review) 0.60f else 0.40f))
    )
    if (review && rating > 0f) {
        Column(surface.padding(horizontal = 7.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.Center) {
                repeat(5) { i -> OverlayReviewStar((rating - i).coerceIn(0f, 1f), Modifier.size(25.dp)) }
            }
            val comment = data?.comment.orEmpty()
            if (comment.isNotBlank()) Text("“$comment”", color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    Row(surface.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val name = data?.iconData.orEmpty()
        val iconColor = data?.let { Color(it.iconColor) } ?: foreground
        val symbol = captionSymbol(type.orEmpty(), data?.iconKind.orEmpty(), name)
        if (symbol != null) {
            val vector = when (symbol) {
                CaptionSymbol.LOCATION -> Icons.Default.LocationOn
                CaptionSymbol.CLOCK -> Icons.Default.AccessTime
                CaptionSymbol.FLAME -> Icons.Default.LocalFireDepartment
                CaptionSymbol.SUN -> Icons.Default.WbSunny
                CaptionSymbol.MOON -> Icons.Default.NightsStay
                CaptionSymbol.PARTLY_CLOUDY -> Icons.Default.WbCloudy
                CaptionSymbol.SNOW -> Icons.Default.AcUnit
                CaptionSymbol.RAIN -> Icons.Default.Grain
                CaptionSymbol.THUNDER -> Icons.Default.Thunderstorm
                CaptionSymbol.CLOUD -> Icons.Default.Cloud
                CaptionSymbol.WIND -> Icons.Default.Air
                CaptionSymbol.FOG -> Icons.Default.Cloud
                CaptionSymbol.SLEET -> Icons.Default.Grain
            }
            val tint = when (symbol) {
                CaptionSymbol.LOCATION -> foreground
                CaptionSymbol.SUN -> Color(0xFFFFC107)
                else -> iconColor
            }
            Icon(vector, null, modifier = Modifier.size(22.dp), tint = tint)
        } else when (data?.iconKind) {
            "emoji" -> if (name.isNotBlank()) Text(name, fontSize = 18.sp)
            "image" -> if (name.startsWith("https://")) SkeletonAsyncImage(name, null, Modifier.size(22.dp))
            else -> if (type == "music") Icon(Icons.Default.MusicNote, null, tint = Color(0xFF1ED760), modifier = Modifier.size(20.dp))
        }
        Text(label, color = foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            maxLines = data?.maxLines ?: 4,
            overflow = TextOverflow.Ellipsis)
    }
}
