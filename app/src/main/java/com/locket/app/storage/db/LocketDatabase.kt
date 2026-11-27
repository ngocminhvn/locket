package com.locket.app.storage.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.locket.app.model.ChatMessage
import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.model.MessageReaction
import com.locket.app.model.Moment
import kotlinx.coroutines.flow.Flow

/**
 * Cache that tren may (Room/SQLite) cho moments, ban be, tro chuyen.
 *
 * Thay the cache JSON trong SharedPreferences lam nguon hien thi chinh:
 * mo app la doc duoc ngay tu DB, khong cho mang. Du lieu trong nay chi la
 * ban sao cua du lieu that da lay tu API (xem LocketCache) — khong co du
 * lieu gia/mock.
 *
 * Token/email van luu trong SharedPreferences (SessionManager), KHONG
 * chuyen sang Room.
 */

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "moments")
data class MomentEntity(
    @PrimaryKey val id: String,
    val userUid: String,
    val userName: String,
    val userAvatarUrl: String = "",
    val thumbnailUrl: String,
    val videoUrl: String? = null,
    val caption: String = "",
    val timestampSeconds: Long = 0L,
    val timeAgo: String = "",
    val md5: String = "",
    val isLocal: Boolean = false,
    // Map<String, Int> reactions, luu dang JSON (xem Converters)
    val reactionsJson: String = "{}"
)

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val uid: String,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val isBestFriend: Boolean = true
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val friendUid: String = "",
    val friendUsername: String = "",
    val friendDisplayName: String = "",
    val friendAvatarUrl: String = "",
    val lastMessage: String = "",
    val timeAgo: String = "",
    val unreadCount: Int = 0
)

/**
 * Tin nhan chat that (xem ChatMessage trong Models.kt).
 * Khoa chinh = id that cua message tren server (doc id Firestore),
 * KHONG sinh id gia. reactions luu dang JSON.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String = "",
    val senderUid: String = "",
    val isMe: Boolean = false,
    val text: String = "",
    val createdAtMs: Long = 0L,
    val clientToken: String? = null,
    val replyMomentUid: String? = null,
    val thumbnailUrl: String? = null,
    val reactionsJson: String = "[]"
)

// ---------------------------------------------------------------------------
// Mappers Entity <-> Model (giu nguyen 1-1, khong them/bot field)
// ---------------------------------------------------------------------------

fun Moment.toEntity() = MomentEntity(
    id = id,
    userUid = userUid,
    userName = userName,
    userAvatarUrl = userAvatarUrl,
    thumbnailUrl = thumbnailUrl,
    videoUrl = videoUrl,
    caption = caption,
    timestampSeconds = timestampSeconds,
    timeAgo = timeAgo,
    md5 = md5,
    isLocal = isLocal,
    reactionsJson = Converters.reactionsToJson(reactions)
)

fun MomentEntity.toModel() = Moment(
    id = id,
    userUid = userUid,
    userName = userName,
    userAvatarUrl = userAvatarUrl,
    thumbnailUrl = thumbnailUrl,
    videoUrl = videoUrl,
    caption = caption,
    timestampSeconds = timestampSeconds,
    timeAgo = timeAgo,
    reactions = Converters.reactionsFromJson(reactionsJson),
    md5 = md5,
    isLocal = isLocal
)

fun Friend.toEntity() = FriendEntity(
    uid = uid,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    isBestFriend = isBestFriend
)

fun FriendEntity.toModel() = Friend(
    uid = uid,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    isBestFriend = isBestFriend
)

fun Conversation.toEntity() = ConversationEntity(
    id = id,
    friendUid = friend.uid,
    friendUsername = friend.username,
    friendDisplayName = friend.displayName,
    friendAvatarUrl = friend.avatarUrl,
    lastMessage = lastMessage,
    timeAgo = timeAgo,
    unreadCount = unreadCount
)

fun ConversationEntity.toModel() = Conversation(
    id = id,
    friend = Friend(
        uid = friendUid,
        username = friendUsername,
        displayName = friendDisplayName,
        avatarUrl = friendAvatarUrl
    ),
    lastMessage = lastMessage,
    timeAgo = timeAgo,
    unreadCount = unreadCount
)

fun ChatMessage.toEntity() = ChatMessageEntity(
    id = id,
    conversationId = conversationId,
    senderUid = senderUid,
    isMe = isMe,
    text = text,
    createdAtMs = createdAtMs,
    clientToken = clientToken,
    replyMomentUid = replyMomentUid,
    thumbnailUrl = thumbnailUrl,
    reactionsJson = Converters.chatReactionsToJson(reactions)
)

fun ChatMessageEntity.toModel() = ChatMessage(
    id = id,
    conversationId = conversationId,
    senderUid = senderUid,
    isMe = isMe,
    text = text,
    createdAtMs = createdAtMs,
    clientToken = clientToken,
    replyMomentUid = replyMomentUid,
    thumbnailUrl = thumbnailUrl,
    reactions = Converters.chatReactionsFromJson(reactionsJson)
)

// ---------------------------------------------------------------------------
// Converters
// ---------------------------------------------------------------------------

object Converters {
    private val gson = Gson()

    @TypeConverter
    fun reactionsToJson(value: Map<String, Int>): String = gson.toJson(value)

    @TypeConverter
    fun reactionsFromJson(value: String): Map<String, Int> {
        return try {
            val type = object : TypeToken<Map<String, Int>>() {}.type
            gson.fromJson(value, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun chatReactionsToJson(value: List<MessageReaction>): String = gson.toJson(value)

    @TypeConverter
    fun chatReactionsFromJson(value: String): List<MessageReaction> {
        return try {
            val type = object : TypeToken<List<MessageReaction>>() {}.type
            gson.fromJson(value, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ---------------------------------------------------------------------------
// DAO
// ---------------------------------------------------------------------------

@Dao
interface LocketDao {
    // Moments: moi nhat len truoc (giong thu tu feed cua API)
    @Query("SELECT * FROM moments ORDER BY timestampSeconds DESC")
    fun observeMoments(): Flow<List<MomentEntity>>

    @Query("SELECT * FROM friends ORDER BY displayName COLLATE NOCASE ASC")
    fun observeFriends(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM conversations")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT COUNT(*) FROM moments")
    suspend fun momentCount(): Int

    @Query("SELECT COUNT(*) FROM friends")
    suspend fun friendCount(): Int

    @Query("DELETE FROM moments")
    suspend fun clearMoments()

    @Query("DELETE FROM friends")
    suspend fun clearFriends()

    @Query("DELETE FROM conversations")
    suspend fun clearConversations()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMoments(moments: List<MomentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriends(friends: List<FriendEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    // Thay toan bo bang trong 1 transaction: khong bao gio thay bang
    // trong do / du lieu nua voi (khop hanh vi cu: sync xong moi thay list).
    @Transaction
    suspend fun replaceMoments(moments: List<MomentEntity>) {
        clearMoments()
        if (moments.isNotEmpty()) insertMoments(moments)
    }

    @Transaction
    suspend fun replaceFriends(friends: List<FriendEntity>) {
        clearFriends()
        if (friends.isNotEmpty()) insertFriends(friends)
    }

    @Transaction
    suspend fun replaceConversations(conversations: List<ConversationEntity>) {
        clearConversations()
        if (conversations.isNotEmpty()) insertConversations(conversations)
    }

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAtMs ASC")
    fun observeChatMessages(conversationId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun chatMessageCount(conversationId: String): Int

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun clearChatMessages(conversationId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllChatMessages()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessages(messages: List<ChatMessageEntity>)

    @Transaction
    suspend fun clearAll() {
        clearMoments()
        clearFriends()
        clearConversations()
        clearAllChatMessages()
    }
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(
    entities = [MomentEntity::class, FriendEntity::class, ConversationEntity::class, ChatMessageEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LocketDatabase : RoomDatabase() {
    abstract fun dao(): LocketDao
}
