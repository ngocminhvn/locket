package com.locket.app.ui

import com.locket.app.ui.screens.CaptionWeather
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionWeatherTest {
    @Test
    fun twilightColorsUseSourceTime() {
        assertEquals(listOf("#AE88FF", "#FFC1AD"), CaptionWeather(20.0, "clear", true, 0.0, "sunrise").colors)
        assertEquals(listOf("#6C77DA", "#FFC896"), CaptionWeather(20.0, "clear", false, 0.0, "sunset").colors)
        assertEquals(listOf("#370C6F", "#575CD4"), CaptionWeather(20.0, "clear", false, 0.0).colors)
    }

    @Test
    fun weatherSymbolsCoverRareConditions() {
        assertEquals("wind", CaptionWeather(20.0, "windy", true, 0.0).symbol)
        assertEquals("cloud.hail.fill", CaptionWeather(0.0, "hail", true, 0.0).symbol)
        assertEquals("wind.snow", CaptionWeather(-2.0, "blizzard", false, 1.0).symbol)
        assertEquals("cloud.rain.fill", CaptionWeather(20.0, "rain", true, 1.0, "sunrise").symbol)
        assertEquals(listOf("#7790A6", "#AAAAAA"), CaptionWeather(20.0, "rain", true, 1.0, "sunset").colors)
        assertEquals("moon.stars.fill", CaptionWeather(20.0, "clearNight", false, 0.0).symbol)
    }
}
