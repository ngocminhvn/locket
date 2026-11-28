package com.locket.app.model

import org.junit.Assert.*
import org.junit.Test

class LinkCaptionTest {
    @Test fun usesVerifiedPayload() {
        val link = requireNotNull(LinkCaption.create("Trang web", "https://example.org/a"))
        val overlay = link.overlay()
        assertEquals("https://example.org/a", overlay.getAsJsonObject("data").getAsJsonObject("payload").get("url").asString)
        assertEquals(link.url, CaptionOverlays.decode(overlay)?.linkUrl)
    }
    @Test fun rejectsUnsafeSchemes() {
        assertNull(LinkCaption.create("Test", "javascript:alert(1)"))
        assertNull(LinkCaption.create("Test", "file:///tmp/foo"))
        assertNull(LinkCaption.create("Test", "https://"))
    }
}
