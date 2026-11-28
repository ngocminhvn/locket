package com.locket.app.api

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailDigestTest {
    @Test
    fun preservesPaddedBase64Md5AcrossReadBoundaries() {
        for (length in listOf(0, 1, 2, 3, 8191, 8192, 8193, 25_000)) {
            val bytes = ByteArray(length) { (it % 251).toByte() }
            val expected = MessageDigest.getInstance("MD5").digest(Base64.getEncoder().encode(bytes))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, bytes.inputStream().use(::thumbnailMd5))
        }
    }
}
