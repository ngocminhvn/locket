package com.locket.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
    private val client = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()
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
private fun SpotifyLinkDialog(initial: CaptionMusic?, onDismiss: () -> Unit, onSelect: (CaptionMusic?) -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicCaptionDialog(initial: CaptionMusic?, onDismiss: () -> Unit, onSelect: (CaptionMusic?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback by SpotifyNowPlaying.state.collectAsState()
    val recent = remember { RecentCaptionMusic.read(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<MusicSearchHit>()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var retrySearch by remember { mutableIntStateOf(0) }
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var linkFallback by remember { mutableStateOf(false) }
    val normalized = query.trim()
    val isBusy = selectedUrl != null
    val green = Color(0xFF1ED760)

    fun finish(music: CaptionMusic?) {
        if (music != null) RecentCaptionMusic.remember(context, music)
        onSelect(music)
    }

    fun openSpotifySearch(term: String) {
        val uri = Uri.parse("https://open.spotify.com/search/" + Uri.encode(term))
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { searchError = "Không mở được Spotify. Hãy dùng liên kết bài hát." }
    }

    fun pick(music: CaptionMusic) {
        if (selectedUrl != null) return
        if (music.artwork != null) { finish(music); return }
        selectedUrl = music.spotifyUrl
        scope.launch {
            try {
                // MusicBrainz/Broadcast supplies the actual title, artist and track relation.
                // Spotify oEmbed only enriches the artwork; do not replace those credits.
                val enriched = try {
                    SpotifyCaptionSource.resolve(music.spotifyUrl)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                finish(music.copy(artwork = enriched?.artwork))
            } finally { selectedUrl = null }
        }
    }

    LaunchedEffect(normalized, linkFallback, retrySearch) {
        val local = recent.filter {
            normalized.isNotBlank() && (it.title.contains(normalized, ignoreCase = true) || it.artist.contains(normalized, ignoreCase = true))
        }.map { MusicSearchHit(it) }
        results = local
        searchError = null
        searching = false
        if (normalized.length < 2 || linkFallback) return@LaunchedEffect
        searching = true
        try {
            delay(550L)
            MusicCaptionCatalog.search(normalized) { hits ->
                results = (local + hits).distinctBy { it.spotifyUrl ?: it.key }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { searchError = e.message ?: "Không tìm được bài hát" }
        finally { searching = false }
    }

    if (linkFallback) {
        SpotifyLinkDialog(initial, onDismiss = { linkFallback = false }, onSelect = { finish(it) })
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF242424),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chọn bài hát", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, enabled = !isBusy,
                placeholder = { Text("Tìm bài hát, nghệ sĩ…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, enabled = !isBusy) { Icon(Icons.Default.Close, "Xóa tìm kiếm") } },
                shape = RoundedCornerShape(24.dp), singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (normalized.length < 2) {
                    item { Text("Spotify", color = green, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                    val live = playback?.takeIf { System.currentTimeMillis() - it.receivedAt < 30 * 60_000L }
                    if (live != null) {
                        item {
                            MusicPickerRow(live.music, null, selectedUrl, enabled = !isBusy,
                                subtitle = when (live.playing) { true -> "Đang phát"; false -> "Đã tạm dừng"; null -> "Bài hát gần nhất" },
                                onClick = { pick(live.music) })
                        }
                    } else {
                        item { Text("Bật Device Broadcast Status trong Spotify, rồi phát hoặc chuyển bài để nhận diện nhạc.",
                            fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 5.dp)) }
                    }
                    if (recent.isNotEmpty()) {
                        item { Text("Đã chọn gần đây", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                        items(recent.filterNot { it.spotifyUrl == live?.music?.spotifyUrl }, key = { it.spotifyUrl }) { song ->
                            MusicPickerRow(song, null, selectedUrl, !isBusy, onClick = { pick(song) })
                        }
                    }
                    if (initial != null) item { TextButton(enabled = !isBusy, onClick = { finish(null) }) { Text("Bỏ bài hát") } }
                } else {
                    item { Text("Kết quả tìm kiếm", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                    items(results, key = { it.key }) { hit ->
                        val music = hit.linkedMusic()
                        if (music != null) {
                            MusicPickerRow(music, hit.coverHint, selectedUrl, !isBusy, onClick = { pick(music) })
                        } else {
                            MusicPickerRow(hit.title, hit.artist, null, false, !isBusy,
                                subtitle = "Tìm bài này trên Spotify",
                                onClick = { openSpotifySearch("${hit.title} ${hit.artist}") })
                        }
                    }
                    if (searching) item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = green)
                            Spacer(Modifier.width(8.dp))
                            Text("Đang tìm bài có liên kết Spotify…", fontSize = 13.sp)
                        }
                    }
                    if (!searching && results.isEmpty() && searchError == null) item {
                        Text("Không tìm thấy bài hát trong MusicBrainz.", color = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.padding(8.dp), fontSize = 14.sp)
                        TextButton(enabled = !isBusy, onClick = { openSpotifySearch(normalized) }) {
                            Text("Tìm trên Spotify", color = green)
                        }
                    }
                    searchError?.let { message ->
                        item {
                            Text(message, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = !isBusy && !searching, onClick = { retrySearch++ }) { Text("Thử lại", color = green) }
                                TextButton(enabled = !isBusy, onClick = { openSpotifySearch(normalized) }) {
                                    Text("Tìm trên Spotify", color = green)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !isBusy, onClick = { linkFallback = true }) { Text("Dán liên kết Spotify", color = green) }
                TextButton(enabled = !isBusy, onClick = onDismiss) { Text("Hủy", color = Color.White) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MusicPickerRow(music: CaptionMusic, coverHint: String?, selectedUrl: String?, enabled: Boolean,
    subtitle: String? = null, onClick: () -> Unit) {
    MusicPickerRow(music.title, music.artist, music.artwork ?: coverHint,
        selectedUrl == music.spotifyUrl, enabled, subtitle, canSelect = true, onClick = onClick)
}

@Composable
private fun MusicPickerRow(title: String, artist: String, artwork: String?, pending: Boolean, enabled: Boolean,
    subtitle: String?, canSelect: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF383838)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = Color(0xFF1ED760), modifier = Modifier.size(24.dp))
            artwork?.let { AsyncImage(it, null, Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = Color(0xFF1ED760), fontSize = 12.sp) }
        }
        if (pending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFF1ED760))
        else Icon(if (canSelect) Icons.Default.AddCircleOutline else Icons.Default.Search,
            if (canSelect) "Chọn bài hát" else "Tìm trên Spotify", tint = Color.White.copy(alpha = 0.75f))
    }
}
