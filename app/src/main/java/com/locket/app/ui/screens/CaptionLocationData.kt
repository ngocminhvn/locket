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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Foreground-only, on-demand location. Never store coordinates or send them to Locket. */
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
            val location = withTimeoutOrNull(8_000L) {
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
            }
            if (location != null) return location
        }
        error("Chưa lấy được vị trí. Hãy bấm lại để thử.")
    }

    @Suppress("DEPRECATION")
    suspend fun placeName(context: Context, location: Location): String = withContext(Dispatchers.IO) {
        check(Geocoder.isPresent()) { "Thiết bị không có dịch vụ tra cứu địa danh" }
        val address = Geocoder(context, Locale.forLanguageTag("vi-VN"))
            .getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            ?: error("Không tìm thấy tên khu vực")
        // Show only a locality/administrative area, never a house number or street address.
        listOfNotNull(address.locality, address.subAdminArea, address.adminArea, address.countryName)
            .map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?: error("Không tìm thấy tên khu vực")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()

    /** Open-Meteo free non-commercial endpoint; no API key. Approximate coordinates only. */
    suspend fun weather(location: Location): String = withContext(Dispatchers.IO) {
        val lat = String.format(Locale.US, "%.2f", location.latitude)
        val lon = String.format(Locale.US, "%.2f", location.longitude)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&temperature_unit=celsius"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Không lấy được thời tiết (HTTP ${response.code})" }
            val current = JSONObject(response.body?.string() ?: error("Thiếu dữ liệu thời tiết")).getJSONObject("current")
            val temperature = current.getDouble("temperature_2m")
            check(temperature.isFinite()) { "Nhiệt độ không hợp lệ" }
            val code = current.getInt("weather_code")
            val day = current.optInt("is_day", 1) == 1
            val symbol = when (code) {
                0 -> if (day) "☀️" else "🌙"
                1, 2 -> if (day) "⛅" else "☁️"
                3 -> "☁️"
                45, 48 -> "🌫️"
                51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
                71, 73, 75, 77, 85, 86 -> "❄️"
                95, 96, 99 -> "⛈️"
                else -> ""
            }
            listOf(symbol, "${temperature.roundToInt()}°C").filter { it.isNotBlank() }.joinToString(" ")
        }
    }
}
