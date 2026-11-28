package com.locket.app.api

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

internal fun thumbnailMd5(input: InputStream): String {
    val md5 = MessageDigest.getInstance("MD5")
    val sink = object : OutputStream() {
        override fun write(value: Int) = md5.update(value.toByte())
        override fun write(bytes: ByteArray, offset: Int, length: Int) = md5.update(bytes, offset, length)
    }
    Base64.getEncoder().wrap(sink).use { input.copyTo(it) }
    return md5.digest().joinToString("") { "%02x".format(it) }
}
