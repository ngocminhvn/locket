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

    private var blockedUntil = 0L

    private suspend fun request(path: String, params: Map<String, String>): JSONObject = throttle.withLock {
        if (now() < blockedUntil) error("Dịch vụ tìm nhạc đang bận. Hãy thử lại sau ít phút.")
        val url = "https://musicbrainz.org/ws/2/$path".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "json").apply {
                params.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "LocketAndroid/1.0 (https://github.com/ngocminhvn/locket/issues)")
            .header("Accept", "application/json").build()
        repeat(2) { attempt ->
            delay((1200L - (now() - lastRequest)).coerceAtLeast(0))
            lastRequest = now()
            val reply = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    Triple(response.code, response.header("Retry-After"), response.body?.string())
                }
            }
            if (reply.first in 200..299) return@withLock JSONObject(reply.third ?: error("Thiếu dữ liệu bài hát"))
            if (reply.first != 503 && reply.first != 429)
                error("Không tìm được bài hát (HTTP ${reply.first}).")
            val wait = reply.second?.toLongOrNull()?.coerceIn(1L, 86_400L)?.times(1000L) ?: 3_000L
            if (attempt == 0 && wait <= 5_000L) delay(wait)
            else {
                blockedUntil = now() + maxOf(wait, 30_000L)
                error("Dịch vụ tìm nhạc đang bận. Hãy thử lại sau ít phút.")
            }
        }
        error("Dịch vụ tìm nhạc đang bận.")
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
        val phrase = escaped(query)
        // Prefer exact phrases but allow title/artist combinations.
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }.take(10)
        val terms = tokens.joinToString(" AND ") { token ->
            val term = escaped(token)
            "(recording:\"$term\" OR artist:\"$term\")"
        }
        val result = try {
            request("recording", mapOf("query" to "(recording:\"$phrase\" OR artist:\"$phrase\" OR ($terms))", "limit" to "8"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val stale = synchronized(cacheLock) { queryCache[key]?.second }
            if (!stale.isNullOrEmpty()) { onResults(stale); return }
            throw e
        }
        val rows = result.optJSONArray("recordings") ?: error("Phản hồi MusicBrainz không hợp lệ")
        val matches = mutableListOf<MusicSearchHit>()
        val seen = mutableSetOf<String>()
        for (i in 0 until minOf(rows.length(), 8)) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeIf { mbid.matches(it) } ?: continue
            val title = row.optString("title").trim()
            val artist = artists(row)
            if (title.isBlank() || artist.isBlank()) continue
            val link = try { spotifyUrl(id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (matches.isNotEmpty()) onResults(matches.toList())
                throw e
            } ?: continue
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
