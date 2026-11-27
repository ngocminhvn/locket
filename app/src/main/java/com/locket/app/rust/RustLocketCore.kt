package com.locket.app.rust

import com.locket.app.model.Moment
import com.locket.app.model.ChatMessage
import com.locket.app.model.MessageReaction
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Batch JNI adapter. Network and lifecycle remain Kotlin-owned. */
object RustLocketCore {
    val isAvailable: Boolean = runCatching { System.loadLibrary("locket_core") }.isSuccess

    private external fun parseFeedNative(responseBody: ByteArray): ByteArray
    private external fun parseHistoryNative(responseBody: ByteArray, ownUid: String, ownOnly: Boolean, limit: Int): ByteArray
    private external fun parseChatNative(responseBody: ByteArray): ByteArray
    private external fun mergeMomentOrderNative(ids: Array<String>, owners: Array<String>, timestamps: LongArray, deleted: Array<String>, hidden: Array<String>): IntArray
    private external fun calculateStreakNative(timestamps: LongArray, nowMillis: Long, timezone: String): Int
    private external fun tokenActionNative(hasToken: Boolean, stale: Boolean, hasRefresh: Boolean): Int
    private external fun needsFullChatRefreshNative(incremental: Boolean, newestCached: String, received: Array<String>): Boolean
    private external fun verifyUpdateManifestNative(responseBody: ByteArray, build: Long, version: String, url: String, size: Long, assetDigest: String): String

    data class FeedDelta(
        val notModified: Boolean,
        val syncToken: String?,
        val moments: List<Moment>,
    )

    fun parseFeed(responseBody: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000): FeedDelta {
        check(isAvailable) { "locket_core native library is unavailable" }
        return FeedWireDecoder.decode(parseFeedNative(responseBody), nowSeconds)
    }

    data class HistoryPage(val moments: List<Moment>, val nextTimestamp: String?, val nextDocument: String?)
    data class ChatPage(val messages: List<ChatMessage>, val nextPageToken: String?)

    fun parseHistory(responseBody: ByteArray, ownUid: String, ownOnly: Boolean, limit: Int = 24, nowSeconds: Long = System.currentTimeMillis() / 1000): HistoryPage {
        check(isAvailable)
        return FeedWireDecoder.decodeHistory(parseHistoryNative(responseBody, ownUid, ownOnly, limit), nowSeconds)
    }

    fun parseChat(responseBody: ByteArray, conversationId: String, myUid: String): ChatPage {
        check(isAvailable)
        return FeedWireDecoder.decodeChat(parseChatNative(responseBody), conversationId, myUid)
    }

    fun mergeMoments(existing: List<Moment>, incoming: List<Moment>, deleted: Set<String>, hiddenOwners: Set<String>): List<Moment> {
        check(isAvailable)
        val combined = existing + incoming
        val order = mergeMomentOrderNative(
            Array(combined.size) { combined[it].id }, Array(combined.size) { combined[it].userUid },
            LongArray(combined.size) { combined[it].timestampSeconds }, deleted.toTypedArray(), hiddenOwners.toTypedArray(),
        )
        require(order.all { it in combined.indices }) { "Rust returned invalid moment index" }
        return order.map(combined::get)
    }

    fun calculateStreak(moments: List<Moment>, nowMillis: Long = System.currentTimeMillis(), timezone: String = java.util.TimeZone.getDefault().id): Int {
        check(isAvailable)
        return calculateStreakNative(LongArray(moments.size) { moments[it].timestampSeconds }, nowMillis, timezone)
            .also { require(it >= 0) }
    }

    enum class TokenAction { MISSING, USE_CURRENT, REFRESH }
    fun tokenAction(hasToken: Boolean, stale: Boolean, hasRefresh: Boolean): TokenAction =
        TokenAction.entries[tokenActionNative(hasToken, stale, hasRefresh)]

    fun needsFullChatRefresh(incremental: Boolean, newestCachedId: String?, received: List<ChatMessage>): Boolean =
        needsFullChatRefreshNative(incremental, newestCachedId.orEmpty(), Array(received.size) { received[it].id })

    fun verifyUpdateManifest(responseBody: ByteArray, build: Long, version: String, url: String, size: Long, assetDigest: String?): String {
        check(isAvailable)
        return verifyUpdateManifestNative(responseBody, build, version, url, size, assetDigest.orEmpty())
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

    fun decodeHistory(bytes: ByteArray, nowSeconds: Long): RustLocketCore.HistoryPage =
        reader(bytes, 2) { input ->
            val timestamp = input.optionalUtf8()
            val document = input.optionalUtf8()
            require((timestamp == null) == (document == null)) { "Incomplete history cursor" }
            val count = input.count()
            val moments = List(count) { input.moment(nowSeconds) }
            require(input.read() == -1) { "Trailing Rust wire bytes" }
            RustLocketCore.HistoryPage(moments, timestamp, document)
        }

    fun decodeChat(bytes: ByteArray, conversationId: String, myUid: String): RustLocketCore.ChatPage =
        reader(bytes, 3) { input ->
            val token = input.optionalUtf8()
            val messages = List(input.count()) {
                val id = input.utf8()
                val sender = input.utf8()
                val body = input.utf8()
                val created = input.readLong()
                val clientToken = input.optionalUtf8()
                val reply = input.optionalUtf8()
                val thumbnail = input.optionalUtf8()
                val reactions = List(input.count()) {
                    MessageReaction(input.utf8(), input.utf8(), input.readLong())
                }
                ChatMessage(id, conversationId, sender, sender == myUid, body, created, clientToken, reply, thumbnail, reactions)
            }
            require(input.read() == -1) { "Trailing Rust wire bytes" }
            RustLocketCore.ChatPage(messages, token)
        }

    private fun <T> reader(bytes: ByteArray, recordType: Int, block: (DataInputStream) -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readNBytes(4).contentEquals("LKT1".toByteArray(Charsets.US_ASCII)))
            require(input.readUnsignedShort() == 1)
            require(input.readUnsignedByte() == recordType)
            block(input)
        }

    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..MAX_RECORDS) }

    private fun DataInputStream.moment(nowSeconds: Long): Moment {
        val id = utf8(); val owner = utf8(); val thumbnail = utf8(); val video = optionalUtf8(); val caption = utf8()
        val seconds = readLong(); val nanos = readInt(); require(nanos in 0..999_999_999); val md5 = utf8()
        return Moment(id, owner, "", "", thumbnail, video, caption, seconds, age(nowSeconds, seconds), emptyMap(), md5)
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
