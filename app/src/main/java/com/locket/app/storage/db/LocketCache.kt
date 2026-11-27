package com.locket.app.storage.db

import com.locket.app.model.ChatMessage
import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.model.Moment
import com.locket.app.storage.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lop cache that duy nhat cua app (Room/SQLite).
 *
 * - Nguon hien thi chinh: UI doc Flow tu Room -> mo app la thay du lieu
 *   cache ngay, khong cho mang. Sync chay nen, xong moi ghi de Room.
 * - Chi luu ban sao du lieu that da lay tu API. Khong sinh du lieu gia.
 * - Dual-write moments/friends sang SharedPreferences JSON cu de widget
 *   (LocketWidgetUpdater doc SessionManager.getSavedMoments()) van chay;
 *   conversations truoc gio chua tung cache trong prefs nen chi luu Room.
 * - Token/email/savedEmail van o SessionManager, khong chuyen sang Room.
 */
class LocketCache(
    private val db: LocketDatabase,
    private val sessionManager: SessionManager
) {
    private val dao: LocketDao get() = db.dao()

    fun observeMoments(): Flow<List<Moment>> =
        dao.observeMoments().map { list -> list.map { it.toModel() } }

    fun observeFriends(): Flow<List<Friend>> =
        dao.observeFriends().map { list -> list.map { it.toModel() } }

    fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { list -> list.map { it.toModel() } }

    fun observeChatMessages(conversationId: String): Flow<List<ChatMessage>> =
        dao.observeChatMessages(conversationId).map { list -> list.map { it.toModel() } }

    suspend fun saveMoments(moments: List<Moment>) {
        dao.reconcileMoments(withContext(Dispatchers.Default) { moments.map { it.toEntity() } })
        // Widget still reads legacy JSON; serialize off the UI thread.
        withContext(Dispatchers.IO) { sessionManager.saveMoments(moments) }
    }

    suspend fun saveFriends(friends: List<Friend>) {
        dao.reconcileFriends(friends.map { it.toEntity() })
        withContext(Dispatchers.IO) { sessionManager.saveFriends(friends) }
    }

    suspend fun saveConversations(conversations: List<Conversation>) {
        // Sua hang UID cu: neu displayName dang la UID nhung bang friends
        // co ten that thi thay bang ten that — khong de hang UID nam lai
        // trong cache roi hien ra du sync moi da resolve duoc.
        val repaired = conversations.map { c ->
            if (c.friend.displayName.isBlank() || c.friend.displayName == c.friend.uid) {
                val cached = dao.getFriend(c.friend.uid)?.toModel()
                if (cached != null && cached.displayName.isNotBlank() && cached.displayName != cached.uid) {
                    c.copy(
                        friend = c.friend.copy(
                            displayName = cached.displayName,
                            username = cached.username.ifBlank { c.friend.username },
                            avatarUrl = cached.avatarUrl.ifBlank { c.friend.avatarUrl }
                        )
                    )
                } else c
            } else c
        }
        dao.reconcileConversations(repaired.map { it.toEntity() })
    }

    /**
     * Luu tin nhan chat: UPSERT theo id that, khong xoa toan bo — fetch chi
     * lay 100 tin moi nhat, xoa het se lam mat tin cu hon chua tai lai.
     * Tin da thu hoi tren server thi xoa khoi cache bang deleteChatMessage
     * (duoi day), khong doi toi clearAll (logout).
     */
    suspend fun saveChatMessages(messages: List<ChatMessage>) {
        if (messages.isNotEmpty()) {
            dao.upsertChangedChatMessages(messages.map { it.toEntity() })
        }
    }

    /** Xoa 1 tin nhan da thu hoi khoi cache Room (khi deleteChatMessage 200). */
    suspend fun deleteChatMessage(messageId: String) {
        dao.deleteChatMessage(messageId)
    }

    suspend fun clearChatMessages(conversationId: String) {
        dao.clearChatMessages(conversationId)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    /**
     * Migrate 1 lan duy nhat: chuyen cache JSON cu trong SharedPreferences
     * (cached_moments / cached_friends) sang Room. Chi chay khi Room con
     * trong; xong danh dau bang co room_cache_migrated_v1 trong prefs.
     * Khong xoa JSON cu — de lam fallback, widget van dung no.
     */
    suspend fun migrateLegacyCacheIfNeeded() {
        if (sessionManager.roomCacheMigrated) return
        if (dao.momentCount() == 0) {
            val legacy = sessionManager.getSavedMoments()
            if (legacy.isNotEmpty()) {
                dao.replaceMoments(legacy.map { it.toEntity() })
            }
        }
        if (dao.friendCount() == 0) {
            val legacy = sessionManager.getFriends()
            if (legacy.isNotEmpty()) {
                dao.replaceFriends(legacy.map { it.toEntity() })
            }
        }
        sessionManager.roomCacheMigrated = true
    }
}
