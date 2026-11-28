package com.locket.app.model

data class User(
    val uid: String,
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val profilePictureUrl: String = "",
    val badge: String = "gold"
)

data class Moment(
    val id: String,
    val userUid: String,
    val userName: String,
    val userAvatarUrl: String = "",
    val thumbnailUrl: String,
    val videoUrl: String? = null,
    val caption: String = "",
    val timestampSeconds: Long = System.currentTimeMillis() / 1000,
    val timeAgo: String = "vừa xong",
    val reactions: Map<String, Int> = emptyMap(),
    val md5: String = "",
    val isLocal: Boolean = false,
    val overlaysJson: String = "[]"
)

data class Friend(
    val uid: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String = "",
    val isBestFriend: Boolean = true
)

/**
 * Loi moi ket ban den (inbox): 1 nguoi gui xin ket ban.
 * Nguon that: document Firestore `users/{uid}/incoming_friend_requests`,
 * field da xac minh trong bundle goc: `requesting_user` = uid nguoi gui
 * (decompiled.js, ham _temp11/_temp13, dong 1227798/1227824).
 * Goc khong doc field ngay gio nao trong doc nay nen model khong co ngay gio.
 */
data class FriendRequest(
    val uid: String,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = ""
)

/**
 * Goi y ket ban (getFriendRecommendations, bundle ~1244458).
 * Response schema goc (FriendRecommendationsResponseSchema):
 * {version: "v1", recommendations: [{user_uid, mutual_friend_count}]}.
 * Ten/avatar resolve tu fetchUsers nhu ban be (xem ApiClient).
 */
data class RecommendedFriend(
    val uid: String,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val mutualFriendCount: Int = 0
)

/**
 * Tai khoan bi chan.
 * Nguon that: document Firestore `users/{uid}/blocked_users/{blockedUid}`
 * (database mac dinh, KHONG phai database `locket` nhu history).
 * Goc: helper blockedUsersCollection(uid) (decompiled.js ~950796, flows 5.1)
 * va BlockedUsersProvider (~1246051): doc id = uid nguoi bi chan, doc
 * data co first_name/last_name (filter doi ca id + ten). Goc khong doc
 * field ngay chan nao trong doc nay nen model khong co ngay gio.
 */
data class BlockedUser(
    val uid: String,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = ""
)

data class Conversation(
    val id: String,
    val friend: Friend,
    val lastMessage: String,
    val timeAgo: String,
    val unreadCount: Int = 0,
    val lastMessageAtMs: Long? = null
)

/**
 * Tin nhan chat that (1-1), khop field cua document Firestore
 * `conversations/{id}/messages` nhu app goc map trong `toMessage`
 * (bundle goc orig/evidence_goc/decompiled.js, ham toMessage):
 * - body: noi dung tin nhan
 * - sender: uid nguoi gui
 * - created_at: Timestamp -> createdAtMs
 * - client_token: token client gui kem (nullable)
 * - reply_moment: uid moment duoc tra loi (nullable)
 * - thumbnail_url: anh thumbnail kem theo (nullable)
 * - reactions: [{sender, emoji, created_at}] (nullable)
 * - deleted_moment: (nullable)
 */
data class MessageReaction(
    val senderUid: String,
    val emoji: String,
    val createdAtMs: Long = 0L
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderUid: String,
    val isMe: Boolean,
    val text: String,
    val createdAtMs: Long = 0L,
    val clientToken: String? = null,
    val replyMomentUid: String? = null,
    val thumbnailUrl: String? = null,
    val reactions: List<MessageReaction> = emptyList()
)

data class CalendarDay(
    val dayNumber: Int,
    val hasMoment: Boolean = false,
    val momentThumbnailUrl: String? = null,
    val isToday: Boolean = false,
    val specialEmoji: String? = null
)

data class CalendarMonth(
    val monthTitle: String,
    val days: List<CalendarDay>
)
