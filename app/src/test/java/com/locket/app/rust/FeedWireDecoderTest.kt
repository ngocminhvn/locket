package com.locket.app.rust

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeedWireDecoderTest {
    @Test
    fun decodesVersionedFeedBatch() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeBytes("LKT1")
            out.writeShort(3)
            out.writeByte(1)
            out.writeByte(0)
            out.writeByte(1)
            out.utf8("token")
            out.writeInt(1)
            out.utf8("moment-a")
            out.utf8("user-a")
            out.utf8("https://example.invalid/a.jpg")
            out.writeByte(0)
            out.utf8("")
            out.writeLong(1_700_000_000)
            out.writeInt(123_456_789)
            out.utf8("digest")
            out.utf8("""[{"overlay_type":"caption","data":{"type":"review"}}]""")
            out.writeByte(1)
            out.utf8("review")
            out.utf8("")
            out.utf8("")
            out.writeInt(-1)
            out.writeInt(0)
            out.writeByte(4)
            out.utf8("")
            out.utf8("")
            out.writeInt(-1)
            out.writeFloat(4.5f)
            out.utf8("Ngon 🍜")
            out.utf8("")
        }

        val decoded = FeedWireDecoder.decode(buffer.toByteArray(), 1_700_000_060)
        assertFalse(decoded.notModified)
        assertEquals("token", decoded.syncToken)
        assertEquals("moment-a", decoded.moments.single().id)
        assertEquals("1m", decoded.moments.single().timeAgo)
        assertEquals(4.5f, decoded.moments.single().overlay!!.rating, 0f)
        assertEquals("Ngon 🍜", decoded.moments.single().overlay!!.comment)
        assertEquals("""[{"overlay_type":"caption","data":{"type":"review"}}]""", decoded.moments.single().overlaysJson)
    }

    private fun DataOutputStream.utf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
