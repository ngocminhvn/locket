package com.locket.app.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.locket.app.LocketApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class SpotifyPlayback(val music: CaptionMusic, val playing: Boolean?, val receivedAt: Long)

internal object SpotifyNowPlaying {
    private const val META = "com.spotify.music.metadatachanged"
    private const val PLAY = "com.spotify.music.playbackstatechanged"
    private val _state = MutableStateFlow<SpotifyPlayback?>(null)
    val state = _state.asStateFlow()
    private var receiver: BroadcastReceiver? = null
    private var playbackAt = 0L

    fun start(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val timestamp = intent.getLongExtra("timeSent", 0L)
                val now = System.currentTimeMillis()
                if (timestamp <= 0 || timestamp > now + 60_000L || now - timestamp > 30 * 60_000L) return
                if (intent.action == META) {
                    val link = SpotifyCaptionSource.canonicalTrack(intent.getStringExtra("id").orEmpty()) ?: return
                    val title = intent.getStringExtra("track").orEmpty().trim().take(250)
                    val artist = intent.getStringExtra("artist").orEmpty().trim().take(250)
                    if (title.isBlank() || artist.isBlank()) return
                    if (timestamp < (_state.value?.receivedAt ?: 0L)) return
                    val playing = if (timestamp >= playbackAt) true else _state.value?.playing
                    _state.value = SpotifyPlayback(CaptionMusic(link, title, artist, null), playing, timestamp)
                } else if (intent.action == PLAY && intent.hasExtra("playing") && timestamp >= playbackAt) {
                    playbackAt = timestamp
                    _state.value?.let { previous ->
                        if (timestamp >= previous.receivedAt) _state.value = previous.copy(
                            playing = intent.getBooleanExtra("playing", false), receivedAt = timestamp)
                    }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(app, listener, IntentFilter().apply { addAction(META); addAction(PLAY) },
                ContextCompat.RECEIVER_EXPORTED)
            receiver = listener
        } catch (_: Exception) { receiver = null }
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        _state.value = null
        playbackAt = 0L
    }
}

/** Persist only tracks the user explicitly selected, scoped to the signed-in account. */
internal object RecentCaptionMusic {
    private fun key(): String? = LocketApplication.instance.sessionManager.currentUser.uid
        .takeIf { it.isNotBlank() }?.let { "selected_tracks_$it" }

    fun read(context: Context): List<CaptionMusic> {
        val key = key() ?: return emptyList()
        return runCatching {
            val array = JSONArray(context.getSharedPreferences("caption_music", Context.MODE_PRIVATE).getString(key, "[]") ?: "[]")
            buildList {
                for (i in 0 until minOf(array.length(), 20)) {
                    val row = array.optJSONObject(i) ?: continue
                    val link = SpotifyCaptionSource.canonicalTrack(row.optString("url")) ?: continue
                    val title = row.optString("title").trim()
                    val artist = row.optString("artist").trim()
                    if (title.isNotBlank() && artist.isNotBlank())
                        add(CaptionMusic(link, title, artist, row.optString("artwork").takeIf { it.startsWith("https://") }))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun remember(context: Context, music: CaptionMusic) {
        val key = key() ?: return
        val items = (listOf(music) + read(context)).distinctBy { it.spotifyUrl }.take(20)
        val array = JSONArray().apply {
            items.forEach { item -> put(JSONObject().apply {
                put("url", item.spotifyUrl); put("title", item.title); put("artist", item.artist)
                item.artwork?.let { put("artwork", it) }
            }) }
        }
        context.getSharedPreferences("caption_music", Context.MODE_PRIVATE).edit().putString(key, array.toString()).apply()
    }
}
