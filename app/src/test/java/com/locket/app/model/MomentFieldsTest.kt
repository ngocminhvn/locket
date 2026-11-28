package com.locket.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomentFieldsTest {
    @Test
    fun partialUpdatesPreserveKnownValuesAndExplicitNullClearsThem() {
        val previous = Moment("id", "owner", "Owner", thumbnailUrl = "thumbnail", caption = "caption",
            videoUrl = "video", md5 = "digest", overlaysJson = """[{"future":true}]""",
            reactions = mapOf("heart" to 1))
        val partial = previous.copy(caption = "", videoUrl = null, md5 = "", overlaysJson = "[]",
            reactions = emptyMap(), missingFields = MOMENT_MISSING_MASK)
        assertEquals(previous, partial.reconcileFields(previous))
        val cleared = partial.copy(missingFields = 0).reconcileFields(previous)
        assertEquals("[]", cleared.overlaysJson)
        assertNull(cleared.videoUrl)
        assertEquals(previous.reactions, cleared.reactions)
    }
}
