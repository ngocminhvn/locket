package com.locket.app.ui.screens

import com.locket.app.model.Moment
import com.locket.app.model.ChatMessage
import java.net.URI

/** Identity of the stored thumbnail, independent of its download token. */
private data class ChatThumbnailKey(val owner: String, val objectPath: String)

private fun chatThumbnailKey(url: String?): ChatThumbnailKey? {
    if (url.isNullOrBlank()) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null) return null
    val prefix = when (uri.host?.lowercase()) {
        "firebasestorage.googleapis.com" -> "/v0/b/locket-img/o/"
        "storage.googleapis.com" -> "/locket-img/"
        "locket-img.storage.googleapis.com" -> "/"
        else -> return null
    }
    val path = uri.path ?: return null
    if (!path.startsWith(prefix)) return null
    val objectPath = path.removePrefix(prefix)
    val parts = objectPath.split('/')
    if (parts.size != 5 || parts[0] != "users" || parts[2] != "moments" ||
        parts[3] != "thumbnails" || parts[1].isBlank() || parts[4].isBlank() ||
        parts.any { it == "." || it == ".." }) return null
    return ChatThumbnailKey(parts[1], objectPath)
}

internal data class ChatMomentAttachment(val ownerUid: String?, val moment: Moment?)

/**
 * Resolve the photo, not the chat sender. Old replies may outlive the feed cache;
 * the verified Locket thumbnail storage path still carries the uploader UID.
 * Never assume that a reply targets the other party (or the sender).
 */
internal class ChatMomentIndex(moments: List<Moment>) {
    private val byId = moments.associateBy { it.id }
    private val byUrl = moments.filter { it.thumbnailUrl.isNotBlank() }.associateBy { it.thumbnailUrl }
    private val byObject = moments.mapNotNull { moment ->
        chatThumbnailKey(moment.thumbnailUrl)?.let { it to moment }
    }.toMap()

    fun resolve(replyMomentUid: String?, thumbnailUrl: String?): ChatMomentAttachment {
        val key = chatThumbnailKey(thumbnailUrl)
        fun Moment.matchesOwner() = userUid.isNotBlank() && (key == null || key.owner == userUid)
        val linked = replyMomentUid?.takeIf { it.isNotBlank() }?.let { byId[it] }?.takeIf { it.matchesOwner() }
            ?: thumbnailUrl?.let { byUrl[it] }?.takeIf { it.matchesOwner() }
            ?: key?.let { byObject[it] }?.takeIf { it.matchesOwner() }
        return ChatMomentAttachment(linked?.userUid ?: key?.owner, linked)
    }
}

/**
 * Keep every real message, but render a consecutive run of replies to the same
 * photo from the same sender with one attachment card. A standalone photo is
 * never hidden, and different canonical IDs never collapse.
 */
internal fun repeatedChatMomentCards(messages: List<ChatMessage>): Set<String> {
    val repeated = mutableSetOf<String>()
    var previous: ChatMessage? = null
    for (message in messages) {
        val prior = previous
        if (prior != null && message.text.isNotBlank() &&
            prior.conversationId == message.conversationId &&
            prior.senderUid == message.senderUid &&
            sameChatMoment(prior, message)
        ) {
            repeated += message.id
        }
        previous = message
    }
    return repeated
}

private fun sameChatMoment(first: ChatMessage, second: ChatMessage): Boolean {
    val aUrl = first.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return false
    val bUrl = second.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return false
    val aId = first.replyMomentUid?.takeIf { it.isNotBlank() }
    val bId = second.replyMomentUid?.takeIf { it.isNotBlank() }
    if (aId != null && bId != null) return aId == bId
    if (aUrl == bUrl) return true
    val key = chatThumbnailKey(aUrl) ?: return false
    return key == chatThumbnailKey(bUrl)
}
