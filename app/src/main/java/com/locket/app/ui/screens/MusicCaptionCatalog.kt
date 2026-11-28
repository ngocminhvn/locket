package com.locket.app.ui.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class MusicSearchHit(val music: CaptionMusic, val coverHint: String? = null)

internal object MusicCaptionCatalog {
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val throttle = Mutex()
    private var lastRequest = -1100L
    private val queryCache = LinkedHashMap<String, Pair<Long, List<MusicSearchHit>>>(24, .75f, true)
    private val relationCache = LinkedHashMap<String, Pair<Long, String?>>(128, .75f, true)
    private val cacheLock = Any()
    private const val TTL = 300_000L
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private val mbid = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")

    private suspend fun request(path: String, params: Map<String, String>): JSONObject = throttle.withLock {
        delay((1100L - (now() - lastRequest)).coerceAtLeast(0))
        lastRequest = now()
        withContext(Dispatchers.IO) {
            val url = "https://musicbrainz.org/ws/2/$path".toHttpUrl().newBuilder()
                .addQueryParameter("fmt", "json").apply {
                    params.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "LocketAndroid/1.0 (https://github.com/ngocminhvn/locket)")
                .header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "MusicBrainz HTTP ${response.code}" }
                JSONObject(response.body?.string() ?: error("Thiếu dữ liệu bài hát"))
            }
        }
    }

    private fun escaped(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun artists(row: JSONObject): String {
        val credits = row.optJSONArray("artist-credit") ?: return ""
        return buildString {
            for (i in 0 until credits.length()) {
                val credit = credits.optJSONObject(i) ?: continue
                append(credit.optString("name").ifBlank { credit.optJSONObject("artist")?.optString("name").orEmpty() })
                append(credit.optString("joinphrase"))
            }
        }.trim()
    }

    private suspend fun spotifyUrl(id: String): String? {
        val cached = synchronized(cacheLock) { relationCache[id]?.takeIf { now() - it.first < TTL } }
        if (cached != null) return cached.second
        val record = request("recording/$id", mapOf("inc" to "url-rels"))
        val relations = record.optJSONArray("relations")
        var link: String? = null
        if (relations != null) for (i in 0 until relations.length()) {
            val rel = relations.optJSONObject(i) ?: continue
            val raw = rel.optJSONObject("url")?.optString("resource").orEmpty()
            link = SpotifyCaptionSource.canonicalTrack(raw)
            if (link != null) break
        }
        synchronized(cacheLock) {
            relationCache[id] = now() to link
            while (relationCache.size > 128) relationCache.remove(relationCache.keys.first())
        }
        return link
    }

    /** Progressive results: only recordings with explicit Spotify track relations. */
    suspend fun search(raw: String, onResults: (List<MusicSearchHit>) -> Unit) {
        val query = raw.trim().take(100)
        if (query.length < 2) { onResults(emptyList()); return }
        val key = query.lowercase(Locale.ROOT)
        synchronized(cacheLock) { queryCache[key]?.takeIf { now() - it.first < TTL } }?.let {
            onResults(it.second)
            return
        }
        val escaped = escaped(query)
        // Prefer exact phrases but allow title/artist combinations.
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }.take(10)
        val terms = tokens.joinToString(" AND ") { token ->
            val term = escaped(token)
            "(recording:\"$term\" OR artist:\"$term\")"
        }
        val result = request("recording", mapOf("query" to "(recording:\"$escaped\" OR artist:\"$escaped\" OR ($terms))", "limit" to "12"))
        val rows = result.optJSONArray("recordings") ?: error("Phản hồi MusicBrainz không hợp lệ")
        val matches = mutableListOf<MusicSearchHit>()
        val seen = mutableSetOf<String>()
        for (i in 0 until minOf(rows.length(), 12)) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeIf { mbid.matches(it) } ?: continue
            val title = row.optString("title").trim()
            val artist = artists(row)
            if (title.isBlank() || artist.isBlank()) continue
            val link = spotifyUrl(id) ?: continue
            if (!seen.add(link)) continue
            val release = row.optJSONArray("releases")?.optJSONObject(0)?.optString("id")
                ?.takeIf { mbid.matches(it) }
            matches += MusicSearchHit(CaptionMusic(link, title, artist, null),
                release?.let { "https://coverartarchive.org/release/$it/front-250" })
            onResults(matches.toList())
            if (matches.size >= 8) break
        }
        synchronized(cacheLock) {
            queryCache[key] = now() to matches.toList()
            while (queryCache.size > 24) queryCache.remove(queryCache.keys.first())
        }
        onResults(matches.toList())
    }
}
