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
    fun uploadAcceptsHalfStarStepsOnly() {
        fun validate(rating: Double): UploadCaption {
            val raw = """[{"overlay_type":"caption","data":{"type":"review","payload":{"rating":$rating,"comment":""}}}]"""
            return UploadCaption.validate("", raw)
        }
        for (step in 1..10) {
            val rating = step / 2.0
            val result = validate(rating)
            assertEquals(rating.toFloat(), CaptionOverlays.fromRaw(result.overlays.toString())!!.rating, 0f)
        }
        for (invalid in listOf(0.0, 0.25, 1.25, 5.5)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { validate(invalid) }
        }
    }

    @Test
    fun malformedOrBlankOverlayJsonIsIgnored() {
        assertNull(CaptionOverlays.first(null))
        assertNull(CaptionOverlays.first(""))
        assertNull(CaptionOverlays.first("not-json"))
    }
}
