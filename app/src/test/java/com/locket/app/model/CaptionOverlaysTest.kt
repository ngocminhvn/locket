package com.locket.app.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionOverlaysTest {
    @Test
    fun nullOverlayJsonFromGsonFallsBackToCaption() {
        val moment = Gson().fromJson(
            """{"id":"moment","userUid":"user","userName":"User","thumbnailUrl":"url","caption":"hello","overlaysJson":null}""",
            Moment::class.java
        )

        assertNull(CaptionOverlays.data(moment))
        assertEquals("hello", CaptionOverlays.label(moment))
    }

    @Test
    fun malformedOrBlankOverlayJsonIsIgnored() {
        assertNull(CaptionOverlays.first(null))
        assertNull(CaptionOverlays.first(""))
        assertNull(CaptionOverlays.first("not-json"))
    }
}
