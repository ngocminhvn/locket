package com.locket.app.rust

import com.locket.app.model.Moment
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Batch JNI adapter. Network and lifecycle remain Kotlin-owned. */
object RustLocketCore {
    val isAvailable: Boolean = runCatching { System.loadLibrary("locket_core") }.isSuccess

    private external fun parseFeedNative(responseBody: ByteArray): ByteArray

    data class FeedDelta(
        val notModified: Boolean,
        val syncToken: String?,
        val moments: List<Moment>,
    )

    fun parseFeed(responseBody: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000): FeedDelta {
        check(isAvailable) { "locket_core native library is unavailable" }
        return FeedWireDecoder.decode(parseFeedNative(responseBody), nowSeconds)
    }
}

internal object FeedWireDecoder {
    private const val MAX_RECORDS = 100_000
    private const val MAX_STRING_BYTES = 8 * 1024 * 1024

    fun decode(bytes: ByteArray, nowSeconds: Long): RustLocketCore.FeedDelta =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readNBytes(4).contentEquals("LKT1".toByteArray(Charsets.US_ASCII))) { "Invalid Rust wire magic" }
            require(input.readUnsignedShort() == 1) { "Unsupported Rust wire schema" }
            require(input.readUnsignedByte() == 1) { "Unexpected Rust record type" }
            val notModified = input.readUnsignedByte() != 0
            val syncToken = input.optionalUtf8()
            val count = input.readInt()
            require(count in 0..MAX_RECORDS) { "Invalid Rust record count" }
            val moments = List(count) {
                val id = input.utf8()
                val owner = input.utf8()
                val thumbnail = input.utf8()
                val video = input.optionalUtf8()
                val caption = input.utf8()
                val seconds = input.readLong()
                val nanos = input.readInt()
                require(nanos in 0..999_999_999) { "Invalid timestamp nanoseconds" }
                val md5 = input.utf8()
                Moment(
                    id = id,
                    userUid = owner,
                    userName = "",
                    thumbnailUrl = thumbnail,
                    videoUrl = video,
                    caption = caption,
                    timestampSeconds = seconds,
                    timeAgo = age(nowSeconds, seconds),
                    md5 = md5,
                )
            }
            require(input.read() == -1) { "Trailing Rust wire bytes" }
            RustLocketCore.FeedDelta(notModified, syncToken, moments)
        }

    private fun DataInputStream.optionalUtf8(): String? = when (readUnsignedByte()) {
        0 -> null
        1 -> utf8()
        else -> throw IllegalArgumentException("Invalid optional string tag")
    }

    private fun DataInputStream.utf8(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid Rust string length" }
        return readNBytes(length).also { require(it.size == length) { "Truncated Rust string" } }.toString(Charsets.UTF_8)
    }

    private fun age(now: Long, timestamp: Long): String {
        val diff = (now - timestamp).coerceAtLeast(0)
        return when {
            diff < 60 -> "vừa xong"
            diff < 3_600 -> "${diff / 60}m"
            diff < 86_400 -> "${diff / 3_600}h"
            else -> "${diff / 86_400}d"
        }
    }
}
