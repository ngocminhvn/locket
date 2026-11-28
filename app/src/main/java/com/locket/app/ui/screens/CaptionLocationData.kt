package com.locket.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Foreground-only, on-demand location. No background tracking. */
internal object CaptionLocationData {
    fun hasPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun currentLocation(context: Context): Location {
        check(hasPermission(context)) { "Chưa cấp quyền vị trí" }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it != LocationManager.GPS_PROVIDER || granted(context, Manifest.permission.ACCESS_FINE_LOCATION) }
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        check(providers.isNotEmpty()) { "Hãy bật vị trí trên điện thoại" }
        for (provider in providers) {
            val location = try { withTimeoutOrNull(8_000L) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    try {
                        LocationManagerCompat.getCurrentLocation(
                            manager, provider, signal, ContextCompat.getMainExecutor(context)
                        ) { result ->
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            if (location != null) return location
        }
        error("Chưa lấy được vị trí. Hãy bấm lại để thử.")
    }

    private fun area(address: android.location.Address): String? =
        listOfNotNull(address.locality, address.subLocality, address.subAdminArea, address.adminArea)
            .map { it.trim() }.firstOrNull { it.isNotBlank() }

    @Suppress("DEPRECATION")
    private suspend fun platformArea(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag("vi-VN"))
        val addresses = if (android.os.Build.VERSION.SDK_INT >= 33) {
            withTimeoutOrNull(3_000L) {
                suspendCancellableCoroutine<List<android.location.Address>> { continuation ->
                    try {
                        geocoder.getFromLocation(location.latitude, location.longitude, 3,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                    if (continuation.isActive) continuation.resume(addresses)
                                }
                                override fun onError(errorMessage: String?) {
                                    if (continuation.isActive) continuation.resume(emptyList())
                                }
                            })
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }.orEmpty()
        } else withContext(Dispatchers.IO) {
            geocoder.getFromLocation(location.latitude, location.longitude, 3).orEmpty()
        }
        return addresses.firstNotNullOfOrNull(::area)
    }

    /** Resolve only the current device location, on the device, after an explicit Location action. */
    suspend fun placeName(context: Context, location: Location): String {
        val local = try { platformArea(context, location) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        if (!local.isNullOrBlank()) return local

        return withContext(Dispatchers.IO) {
            val url = "https://api.bigdatacloud.net/data/reverse-geocode-client".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", location.latitude.toString())
                .addQueryParameter("longitude", location.longitude.toString())
                .addQueryParameter("localityLanguage", "vi").build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Dịch vụ tra cứu địa danh tạm thời không khả dụng. Hãy thử lại." }
                val json = JSONObject(response.body?.string() ?: error("Thiếu dữ liệu địa danh"))
                readAreaName(json) ?: error("Không tìm thấy tên khu vực. Hãy thử lại ở nơi có tín hiệu vị trí tốt hơn.")
            }
        }
    }

    internal fun readAreaName(json: JSONObject): String? =
        listOf("locality", "city", "principalSubdivision")
            .asSequence().filter { !json.isNull(it) }
            .map { json.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()

    private var cachedWeather: Pair<Long, CaptionWeather>? = null

    fun recentWeather(): CaptionWeather? = cachedWeather?.takeIf {
        android.os.SystemClock.elapsedRealtime() - it.first < 300_000L
    }?.second

    /** Open-Meteo direct. Coordinates are rounded before requesting weather. */
    suspend fun weather(location: Location): CaptionWeather = withContext(Dispatchers.IO) {
        val lat = String.format(Locale.US, "%.2f", location.latitude)
        val lon = String.format(Locale.US, "%.2f", location.longitude)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day,cloud_cover&daily=sunrise,sunset&timezone=auto&timeformat=unixtime&temperature_unit=celsius"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Không lấy được thời tiết (HTTP ${response.code})" }
            val document = JSONObject(response.body?.string() ?: error("Thiếu dữ liệu thời tiết"))
            val current = document.getJSONObject("current")
            val temperature = current.getDouble("temperature_2m")
            val cloudCover = current.getDouble("cloud_cover") / 100.0
            check(temperature.isFinite() && cloudCover.isFinite())
            val condition = when (current.getInt("weather_code")) {
                0 -> "clear"
                1 -> "mostlyClear"
                2 -> "partlyCloudy"
                3 -> "cloudy"
                45, 48 -> "foggy"
                51, 53, 55 -> "drizzle"
                56, 57 -> "freezingDrizzle"
                61, 63, 80, 81 -> "rain"
                65, 82 -> "heavyRain"
                66, 67 -> "freezingRain"
                71, 77, 85 -> "flurries"
                73 -> "snow"
                75, 86 -> "heavySnow"
                95 -> "thunderstorms"
                96, 99 -> "strongStorms"
                else -> error("Mã thời tiết không hỗ trợ")
            }
            val now = current.optLong("time", 0L)
            val daily = document.optJSONObject("daily")
            fun near(key: String): Boolean {
                val event = daily?.optJSONArray(key)?.optLong(0, 0L) ?: 0L
                return event > 0L && now > 0L && kotlin.math.abs(now - event) <= 1200L
            }
            val twilight = when { near("sunrise") -> "sunrise"; near("sunset") -> "sunset"; else -> null }
            CaptionWeather(temperature, condition, current.getInt("is_day") == 1, cloudCover.coerceIn(0.0, 1.0), twilight)
                .also { rememberWeather(it) }
        }
    }
    fun rememberWeather(weather: CaptionWeather) {
        cachedWeather = android.os.SystemClock.elapsedRealtime() to weather
    }
}

internal data class CaptionWeather(
    val celsius: Double, val condition: String, val daylight: Boolean, val cloudCover: Double,
    val twilight: String? = null
) {
    private val fahrenheit = celsius * 9 / 5 + 32
    private val useFahrenheit get() = Locale.getDefault().country in setOf("US", "BS", "KY", "LR", "PW", "FM", "MH")
    val label get() = if (useFahrenheit) "${fahrenheit.roundToInt()}°F" else "${celsius.roundToInt()}°C"
    val symbol: String get() = when (condition) {
        "clear", "mostlyClear", "hot" -> if (daylight) "sun.max.fill" else "moon.stars.fill"
        "clearNight", "mostlyClearNight", "hotNight" -> "moon.stars.fill"
        "partlyCloudy", "mostlyCloudy" -> if (daylight) "cloud.sun.fill" else "cloud.moon.fill"
        "partlyCloudyNight" -> "cloud.moon.fill"
        "foggy", "haze", "smoky", "blowingDust" -> "cloud.fog.fill"
        "breezy", "windy" -> "wind"
        "drizzle", "rain", "heavyRain", "freezingRain", "freezingDrizzle", "sunShowers" -> "cloud.rain.fill"
        "snow", "heavySnow", "flurries", "blizzard", "blowingSnow", "sunFlurries", "wintryMix", "frigid" -> "snowflake"
        "sleet", "hail" -> "cloud.sleet.fill"
        "thunderstorms", "strongStorms", "isolatedThunderstorms", "scatteredThunderstorms" -> "cloud.bolt.rain.fill"
        "hurricane", "tropicalStorm" -> "wind"
        else -> "cloud.fill"
    }
    val colors: List<String> get() = when {
        twilight == "sunrise" -> listOf("#AE88FF", "#FFC1AD")
        twilight == "sunset" -> listOf("#6C77DA", "#FFC896")
        condition in setOf("clearNight", "mostlyClearNight", "hotNight", "partlyCloudyNight") -> listOf("#370C6F", "#575CD4")
        condition in setOf("clear", "mostlyClear", "partlyCloudy", "hot") ->
            if (daylight) listOf("#2D9AFF", "#6BDCFF") else listOf("#370C6F", "#575CD4")
        else -> listOf("#7790A6", "#AAAAAA")
    }
    fun overlay() = com.locket.app.model.CaptionOverlays.make("weather", label, colors = colors,
        icon = com.locket.app.model.CaptionOverlays.icon("sf_symbol", symbol),
        payload = com.google.gson.JsonObject().apply {
            addProperty("cloud_cover", cloudCover)
            addProperty("is_daylight", daylight)
            addProperty("temperature", fahrenheit)
            addProperty("wk_condition", condition)
        })
}
