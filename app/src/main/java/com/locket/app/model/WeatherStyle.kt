package com.locket.app.model

import com.locket.app.rust.RustLocketCore

internal data class WeatherStyle(val symbol: String, val colors: List<String>) {
    companion object {
        private val symbols = mapOf(
        "blowingDust" to "wind",
        "clear" to "sun.max.fill",
        "cloudy" to "cloud.fill",
        "foggy" to "cloud.fog.fill",
        "haze" to "sun.haze.fill",
        "mostlyClear" to "sun.max.fill",
        "mostlyCloudy" to "cloud.fill",
        "partlyCloudy" to "cloud.sun.fill",
        "smoky" to "smoke.fill",
        "breezy" to "wind",
        "windy" to "wind",
        "drizzle" to "cloud.drizzle.fill",
        "heavyRain" to "cloud.heavyrain.fill",
        "isolatedThunderstorms" to "cloud.bolt.fill",
        "rain" to "cloud.rain.fill",
        "sunShowers" to "cloud.sun.rain.fill",
        "scatteredThunderstorms" to "cloud.bolt.fill",
        "strongStorms" to "cloud.bolt.rain.fill",
        "thunderstorms" to "cloud.bolt.fill",
        "frigid" to "thermometer.snowflake",
        "hail" to "cloud.hail.fill",
        "hot" to "sun.max.fill",
        "flurries" to "cloud.snow.fill",
        "sleet" to "cloud.sleet.fill",
        "snow" to "cloud.snow.fill",
        "sunFlurries" to "sun.snow.fill",
        "wintryMix" to "cloud.sleet.fill",
        "blizzard" to "wind.snow",
        "blowingSnow" to "wind.snow",
        "freezingDrizzle" to "cloud.drizzle.fill",
        "freezingRain" to "cloud.rain.fill",
        "heavySnow" to "cloud.snow.fill",
        "hurricane" to "hurricane",
        "tropicalStorm" to "tropicalstorm",
        )
        private val twilightConditions = setOf("breezy", "clear", "frigid", "hot", "mostlyClear", "partlyCloudy", "sunFlurries", "sunShowers")
        fun resolve(condition: String, daylight: Boolean, twilight: String?): WeatherStyle {
            // Night variants affect icon only; wk_condition stays the original enum.
            val base = when (condition) {
                "clearNight" -> "clear"
                "mostlyClearNight" -> "mostlyClear"
                "hotNight" -> "hot"
                "partlyCloudyNight" -> "partlyCloudy"
                else -> condition
            }
            val isDay = daylight && base == condition
            RustLocketCore.weatherStyle(base, isDay, twilight.orEmpty())?.let { parts ->
                if (parts.size == 3) return WeatherStyle(parts[0], listOf(parts[1], parts[2]))
            }
            return fallback(base, isDay, twilight)
        }
        private fun fallback(condition: String, daylight: Boolean, twilight: String?): WeatherStyle {
            val symbol = symbols[condition] ?: return WeatherStyle("cloud.fill", listOf("#000000", "#000000"))
            if (condition !in twilightConditions) return WeatherStyle(symbol, listOf("#7790A6", "#AAAAAA"))
            if (twilight == "sunrise") return WeatherStyle("sunrise.fill", listOf("#AE88FF", "#FFC1AD"))
            if (twilight == "sunset") return WeatherStyle("sunset.fill", listOf("#6C77DA", "#FFC896"))
            if (daylight) return WeatherStyle(symbol, listOf("#2D9AFF", "#6BDCFF"))
            val night = when (condition) {
                "clear", "mostlyClear", "hot" -> "moon.stars.fill"
                "partlyCloudy" -> "cloud.moon.fill"
                else -> symbol
            }
            return WeatherStyle(night, listOf("#370C6F", "#575CD4"))
        }
    }
}
