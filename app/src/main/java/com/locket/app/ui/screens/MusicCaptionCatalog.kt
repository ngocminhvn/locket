package com.locket.app.ui.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class MusicSearchHit(
    val key: String,
    val title: String,
    val artist: String,
    val spotifyUrl: String? = null,
    val artwork: String? = null,
    val coverHint: String? = null
) {
    constructor(music: CaptionMusic) : this(music.spotifyUrl, music.title, music.artist, music.spotifyUrl, music.artwork)

    fun linkedMusic(): CaptionMusic? = spotifyUrl?.let { CaptionMusic(it, title, artist, artwork) }
}

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
            link = SpotifyCaptionSource.canonicalTrack(
                if (raw.startsWith("http://", ignoreCase = true)) "https://" + raw.substring(7) else raw
            )
            if (link != null) break
        }
        synchronized(cacheLock) {
            relationCache[id] = now() to link
            while (relationCache.size > 128) relationCache.remove(relationCache.keys.first())
        }
        return link
    }

    /**
     * MetaBrainz's public Labs dataset maps recording MBIDs to actual Spotify
     * track IDs, including tracks without a direct MusicBrainz URL relationship.
     * The documented POST contract returns one result per input in order.
     */
    private suspend fun spotifyBatch(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val body = JSONArray().apply {
            ids.forEach { put(JSONObject().put("recording_mbid", it)) }
        }
        val request = Request.Builder()
            .url("https://labs.api.listenbrainz.org/spotify-id-from-mbid/json")
            .header("User-Agent", "LocketAndroid/1.0 (https://github.com/ngocminhvn/locket/issues)")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Dịch vụ ánh xạ Spotify tạm thời không khả dụng." }
                val rows = JSONArray(response.body?.string() ?: error("Thiếu dữ liệu Spotify"))
                check(rows.length() == ids.size) { "Phản hồi ánh xạ Spotify không hợp lệ." }
                buildMap {
                    ids.forEachIndexed { index, id ->
                        val row = rows.optJSONObject(index) ?: return@forEachIndexed
                        val echoed = row.optString("recording_mbid")
                        if (echoed.isNotBlank() && !echoed.equals(id, ignoreCase = true)) return@forEachIndexed
                        val tracks = row.optJSONArray("spotify_track_ids") ?: return@forEachIndexed
                        for (i in 0 until tracks.length()) {
                            val raw = tracks.optString(i)
                            val url = SpotifyCaptionSource.canonicalTrack(raw)
                                ?: raw.takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }
                                    ?.let { SpotifyCaptionSource.canonicalTrack("spotify:track:$it") }
                            if (url != null) {
                                put(id, url)
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun search(raw: String, onResults: (List<MusicSearchHit>) -> Unit) {
        val query = raw.trim().take(100)
        if (query.length < 2) { onResults(emptyList()); return }
        val key = query.lowercase(Locale.ROOT)
        synchronized(cacheLock) { queryCache[key]?.takeIf { now() - it.first < TTL } }?.let {
            onResults(it.second)
            return
        }
        val phrase = escaped(query)
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }.take(10)
        val terms = tokens.joinToString(" AND ") { token ->
            val term = escaped(token)
            "(recording:\"$term\" OR artist:\"$term\")"
        }
        val result = try {
            request("recording", mapOf("query" to "(recording:\"$phrase\"^4 OR artist:\"$phrase\" OR ($terms))", "limit" to "25"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val stale = synchronized(cacheLock) { queryCache[key]?.second }
            if (!stale.isNullOrEmpty()) { onResults(stale); return }
            throw e
        }
        val rows = result.optJSONArray("recordings") ?: error("Phản hồi MusicBrainz không hợp lệ")
        val candidates = (0 until minOf(rows.length(), 25)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { mbid.matches(it) } ?: return@mapNotNull null
            val title = row.optString("title").trim()
            val artist = artists(row)
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val release = row.optJSONArray("releases")?.optJSONObject(0)?.optString("id")
                ?.takeIf { mbid.matches(it) }
            MusicSearchHit(id, title, artist, coverHint = release?.let { "https://coverartarchive.org/release/$it/front-250" })
        }.distinctBy { it.key }
        val links = mutableMapOf<String, String>()
        fun visible(): List<MusicSearchHit> {
            val (linked, unlinked) = candidates.partition { it.key in links }
            val enriched = linked.map { it.copy(spotifyUrl = links[it.key]) }.distinctBy { it.spotifyUrl }
            return (enriched + unlinked.distinctBy { it.title to it.artist }).take(15)
        }
        onResults(visible())

        var mappingAvailable = true
        try {
            links.putAll(spotifyBatch(candidates.map { it.key }))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { mappingAvailable = false }
        if (links.isNotEmpty()) onResults(visible())

        // At most three direct MusicBrainz lookups when the batch leaves few matches.
        if (links.values.toSet().size < 5) {
            for (candidate in candidates.filterNot { it.key in links }.take(3)) {
                val link = try { spotifyUrl(candidate.key) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { mappingAvailable = false; break }
                if (link != null) {
                    links[candidate.key] = link
                    onResults(visible())
                }
            }
        }
        if (mappingAvailable) synchronized(cacheLock) {
            queryCache[key] = now() to visible()
            while (queryCache.size > 24) queryCache.remove(queryCache.keys.first())
        }
    }
}
