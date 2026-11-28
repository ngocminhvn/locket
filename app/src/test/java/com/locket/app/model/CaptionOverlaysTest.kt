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

        assertNull(moment.overlay)
        assertEquals("hello", CaptionOverlays.label(moment))
    }

    @Test
    fun preservesHalfStarsAndCssAlpha() {
        val overlay = CaptionOverlays.fromRaw(
            """[null,{"overlay_type":"caption","data":{"type":"future"}},{"overlay_type":"caption","data":{"type":"review","text_color":"#11223380","payload":{"rating":4.5,"comment":"Ngon 🍜"}}}]"""
        )!!
        assertEquals(4.5f, overlay.rating, 0f)
        assertEquals(0x80112233.toInt(), overlay.foreground)
        assertEquals("Ngon 🍜", overlay.comment)
    }

    @Test
    fun malformedOrBlankOverlayJsonIsIgnored() {
        assertNull(CaptionOverlays.first(null))
        assertNull(CaptionOverlays.first(""))
        assertNull(CaptionOverlays.first("not-json"))
    }
}
