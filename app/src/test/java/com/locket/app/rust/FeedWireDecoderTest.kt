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
            out.writeShort(1)
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
        }

        val decoded = FeedWireDecoder.decode(buffer.toByteArray(), 1_700_000_060)
        assertFalse(decoded.notModified)
        assertEquals("token", decoded.syncToken)
        assertEquals("moment-a", decoded.moments.single().id)
        assertEquals("1m", decoded.moments.single().timeAgo)
    }

    private fun DataOutputStream.utf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
