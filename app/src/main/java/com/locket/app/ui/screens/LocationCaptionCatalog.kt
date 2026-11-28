package com.locket.app.ui.screens

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class CaptionPlace(
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val category: String,
    val distanceMeters: Float? = null,
    val osmId: String = ""
) {
    fun toLocation(): Location = Location("openstreetmap").apply {
        latitude = this@CaptionPlace.latitude
        longitude = this@CaptionPlace.longitude
    }
}

/** Photon search and nearby OSM POIs. Requests are user-triggered, cached and serialized. */
internal object LocationCaptionCatalog {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L
    private val cache = LinkedHashMap<String, Pair<Long, List<CaptionPlace>>>(48, .75f, true)
    private const val CACHE_TTL_MS = 180_000L

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun cached(key: String): List<CaptionPlace>? = synchronized(cache) {
        cache[key]?.takeIf { now() - it.first < CACHE_TTL_MS }?.second
    }

    private fun remember(key: String, value: List<CaptionPlace>) = synchronized(cache) {
        cache[key] = now() to value
        while (cache.size > 48) cache.remove(cache.keys.first())
    }

    private suspend fun fetch(url: HttpUrl): JSONObject = requestMutex.withLock {
        delay((1_100L - (now() - lastRequestAt)).coerceAtLeast(0L))
        lastRequestAt = now()
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "LocketAndroid/1.0 (https://github.com/ngocminhvn/locket)")
                    .header("Accept", "application/geo+json, application/json")
                    .build()
            ).execute().use { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        429, 503 -> "Dịch vụ địa điểm đang bận. Hãy thử lại sau."
                        else -> "Không tìm được địa điểm (HTTP ${response.code})."
                    }
                }
                JSONObject(response.body?.string() ?: error("Không có dữ liệu địa điểm"))
            }
        }
    }

    suspend fun nearby(location: Location): List<CaptionPlace> {
        val lat = String.format(Locale.US, "%.4f", location.latitude)
        val lon = String.format(Locale.US, "%.4f", location.longitude)
        val key = "nearby:$lat:$lon"
        cached(key)?.let { return it }
        val url = "https://photon.komoot.io/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", lat)
            .addQueryParameter("lon", lon)
            .addQueryParameter("radius", "3")
            .addQueryParameter("limit", "25")
            .addQueryParameter("lang", "vi")
            .apply {
                // Ask Photon for nearby named points of interest, not just the nearest street address.
                listOf("amenity", "shop", "tourism", "leisure", "office").forEach {
                    addQueryParameter("osm_tag", it)
                }
            }.build()
        val places = parse(fetch(url), location)
        remember(key, places)
        return places
    }

    suspend fun search(query: String, location: Location): List<CaptionPlace> {
        val term = query.trim().take(100)
        if (term.length < 2) return emptyList()
        val lat = String.format(Locale.US, "%.3f", location.latitude)
        val lon = String.format(Locale.US, "%.3f", location.longitude)
        val key = "search:${term.lowercase(Locale.ROOT)}:$lat:$lon"
        cached(key)?.let { return it }
        val url = "https://photon.komoot.io/api".toHttpUrl().newBuilder()
            .addQueryParameter("q", term)
            .addQueryParameter("lat", lat)
            .addQueryParameter("lon", lon)
            .addQueryParameter("zoom", "16")
            .addQueryParameter("location_bias_scale", "0.15")
            .addQueryParameter("limit", "15")
            .addQueryParameter("lang", "vi")
            .build()
        val places = parse(fetch(url), location)
        remember(key, places)
        return places
    }

    private fun parse(json: JSONObject, origin: Location): List<CaptionPlace> {
        val features = json.optJSONArray("features") ?: return emptyList()
        val result = LinkedHashMap<String, CaptionPlace>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val name = properties.optString("name").trim()
            if (name.isBlank() || name.equals("null", ignoreCase = true)) continue
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue
            val longitude = coordinates.optDouble(0, Double.NaN)
            val latitude = coordinates.optDouble(1, Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue

            val key = "${properties.optString("osm_type")}:${properties.optString("osm_id")}"
                .takeIf { !it.endsWith(":") } ?: "${name.lowercase(Locale.ROOT)}:${"%.4f".format(Locale.US, latitude)}:${"%.4f".format(Locale.US, longitude)}"
            val address = listOf("street", "district", "city", "county", "state")
                .map { properties.optString(it).trim() }
                .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                .distinct().take(3).joinToString(", ")
            val category = categoryName(properties.optString("osm_key"), properties.optString("osm_value"))
            val distance = FloatArray(1)
            Location.distanceBetween(origin.latitude, origin.longitude, latitude, longitude, distance)
            result.putIfAbsent(key, CaptionPlace(
                name = name,
                subtitle = listOf(category, address).filter { it.isNotBlank() }.joinToString(" · "),
                latitude = latitude,
                longitude = longitude,
                category = category,
                distanceMeters = distance[0],
                osmId = key
            ))
        }
        return result.values.sortedBy { it.distanceMeters ?: Float.MAX_VALUE }
    }

    private fun categoryName(key: String, value: String): String {
        val normalized = value.replace('_', ' ').trim().lowercase(Locale.ROOT)
        return when (key.lowercase(Locale.ROOT)) {
            "amenity" -> when (normalized) {
                "cafe" -> "Quán cà phê"
                "restaurant", "fast food", "food court" -> "Ăn uống"
                "school", "university", "college", "kindergarten" -> "Trường học"
                "hospital", "clinic", "doctors", "pharmacy" -> "Y tế"
                "bank", "atm" -> "Ngân hàng / ATM"
                "fuel" -> "Trạm xăng"
                "place of worship" -> "Cơ sở tôn giáo"
                "marketplace" -> "Chợ"
                else -> "Địa điểm"
            }
            "shop" -> when (normalized) {
                "supermarket", "convenience" -> "Cửa hàng"
                "mall" -> "Trung tâm thương mại"
                "bakery" -> "Tiệm bánh"
                else -> "Cửa hàng"
            }
            "tourism" -> when (normalized) {
                "hotel", "guest house" -> "Lưu trú"
                "museum" -> "Bảo tàng"
                "attraction" -> "Điểm tham quan"
                else -> "Du lịch"
            }
            "leisure" -> when (normalized) {
                "park", "garden" -> "Công viên"
                "sports centre", "pitch" -> "Thể thao"
                else -> "Giải trí"
            }
            "office" -> "Văn phòng"
            else -> "Địa điểm"
        }
    }
}
