package com.locket.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSymbolTest {
    @Test
    fun locationNeverFallsBackToWeather() {
        for ((iconKind, iconData) in listOf(
            "sf_symbol" to "mappin.and.ellipse",
            "sf_symbol" to "location.fill",
            "sf_symbol" to "unknown.symbol",
            "sf_symbol" to "cloud.fill",
            "" to ""
        )) {
            assertEquals(CaptionSymbol.LOCATION, captionSymbol("location", iconKind, iconData))
        }
    }

    @Test
    fun weatherAndUnknownSymbolsRemainDistinct() {
        assertEquals(CaptionSymbol.CLOUD, captionSymbol("weather", "sf_symbol", "cloud.fill"))
        assertEquals(CaptionSymbol.SUN, captionSymbol("weather", "sf_symbol", "sun.max.fill"))
        assertEquals(CaptionSymbol.RAIN, captionSymbol("weather", "sf_symbol", "cloud.rain.fill"))
        assertEquals(CaptionSymbol.WIND, captionSymbol("weather", "sf_symbol", "wind"))
        assertEquals(CaptionSymbol.FOG, captionSymbol("weather", "sf_symbol", "cloud.fog.fill"))
        assertEquals(CaptionSymbol.SLEET, captionSymbol("weather", "sf_symbol", "cloud.sleet.fill"))
        assertEquals(CaptionSymbol.SNOW, captionSymbol("weather", "sf_symbol", "cloud.snow.fill"))
        assertNull(captionSymbol("standard", "sf_symbol", "unknown.symbol"))
        assertNull(captionSymbol("weather", "sf_symbol", "unknown.symbol"))
    }
}
