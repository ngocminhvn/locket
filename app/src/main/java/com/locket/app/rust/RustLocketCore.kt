package com.locket.app.rust

import com.locket.app.model.Moment
import com.locket.app.model.MOMENT_MISSING_MASK
import com.locket.app.model.CaptionOverlay
import com.locket.app.model.CaptionOverlays
import java.nio.ByteBuffer
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
    private val utf8Decoder = ThreadLocal.withInitial { Charsets.UTF_8.newDecoder() }

    fun decode(bytes: ByteArray, nowSeconds: Long): RustLocketCore.FeedDelta =
        reader(bytes, 1) { input ->
            val notModified = input.bool()
            val syncToken = input.optionalUtf8()
            val moments = List(input.count()) { input.moment(nowSeconds) }
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
            require(input.readInt() == 0x4c4b5431)
            require(input.readUnsignedShort() == 4)
            require(input.readUnsignedByte() == recordType)
            block(input)
        }

    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..MAX_RECORDS) }

    private fun DataInputStream.moment(nowSeconds: Long): Moment {
        val id = utf8()
        val owner = utf8()
        val thumbnail = utf8()
        val video = optionalUtf8()
        val caption = utf8()
        val seconds = readLong()
        val nanos = readInt()
        require(nanos in 0..999_999_999)
        val md5 = utf8()
        val raw = utf8()
        val missing = readUnsignedByte().also { require(it in 0..MOMENT_MISSING_MASK) }
        val overlay = if (bool()) captionOverlay() else null
        CaptionOverlays.cacheDecoded(raw, overlay)
        return Moment(id, owner, "", "", thumbnail, video, caption, seconds, age(nowSeconds, seconds),
            md5 = md5, overlaysJson = raw, missingFields = missing).also { it.setDecodedOverlay(overlay) }
    }

    private fun DataInputStream.captionOverlay(): CaptionOverlay {
        val kind = utf8(32)
        require(CaptionOverlays.isSupported(kind))
        val text = utf8(4096)
        val altText = utf8(4096)
        val foreground = readInt()
        val colors = List(readInt().also { require(it in 0..16) }) { readInt() }
        val maxLines = readUnsignedByte().also { require(it in 1..4) }
        val iconKind = utf8(4096)
        val iconData = utf8(4096)
        val iconColor = readInt()
        val rating = readFloat().also { require(it in 0f..5f) }
        return CaptionOverlay(kind, text, altText, foreground, colors, maxLines, iconKind, iconData,
            iconColor, rating, utf8(4096), utf8(4096))
    }

    private fun DataInputStream.bool(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid Rust boolean tag")
    }

    private fun DataInputStream.optionalUtf8(): String? = when (readUnsignedByte()) {
        0 -> null
        1 -> utf8()
        else -> throw IllegalArgumentException("Invalid optional string tag")
    }

    private fun DataInputStream.utf8(maxBytes: Int = MAX_STRING_BYTES): String {
        val length = readInt()
        require(length in 0..maxBytes) { "Invalid Rust string length" }
        if (length == 0) return ""
        require(length <= available()) { "Truncated Rust string" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return checkNotNull(utf8Decoder.get()).decode(ByteBuffer.wrap(bytes)).toString()
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
