package com.locket.app.ui

import com.locket.app.model.UploadCaption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MomentUploadPolicyTest {
    @Test
    fun resumeRequiresNoPostMarkerOrRecordedFailure() {
        assertEquals(MomentUploadRecovery.RESUME, uploadRecovery(false, false, false))
        assertEquals(MomentUploadRecovery.REQUIRE_USER, uploadRecovery(false, false, true))
        assertEquals(MomentUploadRecovery.REQUIRE_USER, uploadRecovery(false, true, false))
        assertEquals(MomentUploadRecovery.CLEANUP, uploadRecovery(true, true, true))
    }

    @Test
    fun outgoingReviewKeepsPayloadAndOnlyStandardUsesTopLevelCaption() {
        val review = """[{"overlay_type":"caption","data":{"type":"review","payload":{"rating":4,"comment":"🍜","future":{"a":[1,null]}}}}]"""
        val caption = UploadCaption.validate("legacy", review)
        assertEquals("", caption.caption)
        assertEquals(review, caption.overlays.toString())
        assertEquals("hello", UploadCaption.validate("hello", """[{"overlay_type":"caption","data":{"type":"standard"}}]""").caption)
        assertThrows(IllegalArgumentException::class.java) { UploadCaption.validate("", review.replace(":4,", ":4.5,")) }
        assertThrows(IllegalArgumentException::class.java) { UploadCaption.validate("", review.replace("review", "poll")) }
    }
}
