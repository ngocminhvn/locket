package com.locket.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.gson.JsonObject
import com.locket.app.model.CaptionOverlays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class CaptionMusic(
    val spotifyUrl: String,
    val title: String,
    val artist: String,
    val artwork: String?
) {
    val label get() = "$title $artist".trim()
    fun overlay() = CaptionOverlays.make("music", label, blur = "ultra_thin",
        icon = artwork?.let { CaptionOverlays.icon("image", it).apply { addProperty("source", "url") } },
        payload = JsonObject().apply {
            addProperty("song_title", title)
            addProperty("artist", artist)
            addProperty("spotify_url", spotifyUrl)
            // oEmbed does not supply ISRC or audio preview URLs. Never invent them.
        })
}

internal object SpotifyCaptionSource {
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val trackId = Regex("^[A-Za-z0-9]{22}$")

    fun canonicalTrack(raw: String): String? {
        val value = raw.trim()
        val id = if (value.startsWith("spotify:track:")) value.removePrefix("spotify:track:") else {
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host != "open.spotify.com" || uri.userInfo != null || uri.port != -1) return null
            val parts = uri.path.orEmpty().trim('/').split('/')
            if (parts.size < 2 || parts[parts.size - 2] != "track" ||
                (parts.size != 2 && !(parts.size == 3 && parts.first().startsWith("intl-")))) return null
            parts.last()
        }
        return id.takeIf { trackId.matches(it) }?.let { "https://open.spotify.com/track/$it" }
    }

    suspend fun resolve(raw: String): CaptionMusic = withContext(Dispatchers.IO) {
        val url = canonicalTrack(raw) ?: error("Hãy dán liên kết bài hát Spotify, không phải album hoặc playlist.")
        val endpoint = "https://open.spotify.com/oembed".toHttpUrl().newBuilder().addQueryParameter("url", url).build()
        client.newCall(Request.Builder().url(endpoint).build()).execute().use { response ->
            check(response.isSuccessful) { "Spotify không trả thông tin bài hát (HTTP ${response.code})" }
            val data = JSONObject(response.body?.string() ?: error("Thiếu dữ liệu Spotify"))
            val title = data.optString("title").trim()
            check(title.isNotBlank()) { "Không tìm thấy tên bài hát" }
            val artist = data.optString("author_name").trim()
            val artwork = data.optString("thumbnail_url").takeIf { rawUrl ->
                val uri = runCatching { java.net.URI(rawUrl) }.getOrNull()
                uri?.scheme == "https" && uri.host?.let { it == "i.scdn.co" || it.endsWith(".scdn.co") || it.endsWith(".spotifycdn.com") } == true
            }
            CaptionMusic(url, title, artist, artwork)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MusicCaptionDialog(initial: CaptionMusic?, onDismiss: () -> Unit, onSelect: (CaptionMusic?) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf(initial?.spotifyUrl.orEmpty()) }
    var track by remember { mutableStateOf(initial) }
    var artist by remember { mutableStateOf(initial?.artist.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun setLink(value: String) {
        link = value.take(2048)
        if (SpotifyCaptionSource.canonicalTrack(link) != track?.spotifyUrl) {
            track = null
            artist = ""
        }
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chọn bài hát") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sao chép liên kết bài hát từ Spotify rồi dán vào đây.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search"))) }
                            .onFailure { error = "Không mở được Spotify" }
                    }) { Text("Mở Spotify") }
                    TextButton(enabled = !loading, onClick = { setLink(clipboard.getText()?.text.orEmpty()) }) { Text("Dán liên kết") }
                }
                OutlinedTextField(value = link, onValueChange = ::setLink, label = { Text("Liên kết Spotify") },
                    enabled = !loading, singleLine = true, modifier = Modifier.fillMaxWidth())
                FilledTonalButton(enabled = !loading && link.isNotBlank(), onClick = {
                    val requested = link
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            val result = SpotifyCaptionSource.resolve(requested)
                            track = result
                            artist = result.artist
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Không lấy được bài hát" }
                        finally { loading = false }
                    }
                }) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Lấy thông tin bài hát")
                }
                track?.let { current ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        current.artwork?.let { AsyncImage(it, null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) }
                        Text(current.title, fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(value = artist, onValueChange = { artist = it.take(160) }, label = { Text("Nghệ sĩ") },
                        supportingText = { Text("Kiểm tra hoặc nhập tên nghệ sĩ theo Spotify.") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && track != null && artist.isNotBlank() &&
                track?.spotifyUrl == SpotifyCaptionSource.canonicalTrack(link),
                onClick = { track?.let { onSelect(it.copy(artist = artist.trim())) } }) { Text("Chọn") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onSelect(null) }) { Text("Bỏ bài hát") }
                TextButton(onClick = onDismiss) { Text("Hủy") }
            }
        }
    )
}
