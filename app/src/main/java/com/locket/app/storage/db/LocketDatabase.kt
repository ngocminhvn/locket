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
    val reactionsJson: String = "{}",
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val overlaysJson: String = "[]"
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
    val unreadCount: Int = 0,
    val lastMessageAtMs: Long? = null
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
    reactionsJson = Converters.reactionsToJson(reactions),
    overlaysJson = overlaysJson
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
    isLocal = isLocal,
    overlaysJson = overlaysJson
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
    unreadCount = unreadCount,
    lastMessageAtMs = lastMessageAtMs
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
    unreadCount = unreadCount,
    lastMessageAtMs = lastMessageAtMs
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

    @Query("SELECT * FROM conversations ORDER BY lastMessageAtMs DESC, id ASC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM friends WHERE uid = :uid LIMIT 1")
    suspend fun getFriend(uid: String): FriendEntity?

    @Query("SELECT COUNT(*) FROM moments")
    suspend fun momentCount(): Int

    @Query("SELECT COUNT(*) FROM friends")
    suspend fun friendCount(): Int

    @Query("SELECT * FROM moments")
    suspend fun snapshotMoments(): List<MomentEntity>

    @Query("SELECT * FROM friends")
    suspend fun snapshotFriends(): List<FriendEntity>

    @Query("SELECT * FROM conversations")
    suspend fun snapshotConversations(): List<ConversationEntity>

    @Query("SELECT * FROM chat_messages WHERE id IN (:ids)")
    suspend fun snapshotChatMessages(ids: List<String>): List<ChatMessageEntity>

    @Query("DELETE FROM moments WHERE id IN (:ids)")
    suspend fun deleteMoments(ids: List<String>)

    @Query("DELETE FROM friends WHERE uid IN (:ids)")
    suspend fun deleteFriends(ids: List<String>)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteConversations(ids: List<String>)

    @Transaction
    suspend fun reconcileMoments(moments: List<MomentEntity>) {
        val old = snapshotMoments().associateBy { it.id }
        val keys = moments.mapTo(HashSet()) { it.id }
        (old.keys - keys).chunked(400).forEach { deleteMoments(it) }
        val changed = moments.filter { old[it.id] != it }
        if (changed.isNotEmpty()) insertMoments(changed)
    }

    @Transaction
    suspend fun reconcileFriends(friends: List<FriendEntity>) {
        val old = snapshotFriends().associateBy { it.uid }
        val keys = friends.mapTo(HashSet()) { it.uid }
        (old.keys - keys).chunked(400).forEach { deleteFriends(it) }
        val changed = friends.filter { old[it.uid] != it }
        if (changed.isNotEmpty()) insertFriends(changed)
    }

    @Transaction
    suspend fun reconcileConversations(conversations: List<ConversationEntity>) {
        val old = snapshotConversations().associateBy { it.id }
        val keys = conversations.mapTo(HashSet()) { it.id }
        (old.keys - keys).chunked(400).forEach { deleteConversations(it) }
        val changed = conversations.filter { old[it.id] != it }
        if (changed.isNotEmpty()) insertConversations(changed)
    }

    @Transaction
    suspend fun upsertChangedChatMessages(messages: List<ChatMessageEntity>) {
        for (batch in messages.chunked(400)) {
            val old = snapshotChatMessages(batch.map { it.id }).associateBy { it.id }
            val changed = batch.filter { old[it.id] != it }
            if (changed.isNotEmpty()) insertChatMessages(changed)
        }
    }

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

    // Xoa 1 tin nhan da thu hoi (deleteChatMessage) khoi cache: chi 1 cau
    // DELETE theo id, khong doi bang/schema (khong tang version DB).
    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteChatMessage(messageId: String)

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
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LocketDatabase : RoomDatabase() {
    abstract fun dao(): LocketDao
}
