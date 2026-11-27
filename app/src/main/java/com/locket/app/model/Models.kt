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
    val isLocal: Boolean = false
)

data class Friend(
    val uid: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String = "",
    val isBestFriend: Boolean = true
)

data class Conversation(
    val id: String,
    val friend: Friend,
    val lastMessage: String,
    val timeAgo: String,
    val unreadCount: Int = 0
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
