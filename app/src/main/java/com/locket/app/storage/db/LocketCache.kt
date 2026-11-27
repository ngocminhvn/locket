package com.locket.app.storage.db

import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.model.Moment
import com.locket.app.storage.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    suspend fun saveMoments(moments: List<Moment>) {
        dao.replaceMoments(moments.map { it.toEntity() })
        // Dual-write: widget van doc cache JSON cu trong prefs.
        sessionManager.saveMoments(moments)
    }

    suspend fun saveFriends(friends: List<Friend>) {
        dao.replaceFriends(friends.map { it.toEntity() })
        sessionManager.saveFriends(friends)
    }

    suspend fun saveConversations(conversations: List<Conversation>) {
        dao.replaceConversations(conversations.map { it.toEntity() })
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
