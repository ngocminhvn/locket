package com.locket.app.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.locket.app.model.Friend
import com.locket.app.model.Moment
import com.locket.app.model.User

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("locket_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Scope the server-issued cursor to the authenticated account.
    var feedSyncToken: String?
        get() = prefs.getString("feed_sync_token_" + currentUser.uid, null)
        set(value) = prefs.edit().putString("feed_sync_token_" + currentUser.uid, value).apply()

    // Ngay YYYYMMDD (Gregorian) gan nhat da bao server cap nhat streak qua
    // postMomentV2 (field update_streak_for_yyyymmdd). Goc so sanh voi
    // streak.last_updated_yyyymmdd (bundle ~1257420-1257480): khac ngay
    // hom nay thi gui field, giong thi khong gui. Luu theo uid nhu
    // feedSyncToken; logout xoa het prefs nen reset theo tai khoan.
    var streakLastUpdatedYyyymmdd: String?
        get() = prefs.getString("streak_last_updated_yyyymmdd_" + currentUser.uid, null)
        set(value) = prefs.edit().putString("streak_last_updated_yyyymmdd_" + currentUser.uid, value).apply()

    var savedEmail: String?
        get() = prefs.getString("saved_email", null)
        set(value) = prefs.edit().putString("saved_email", value).apply()

    var tokenObtainedAtMs: Long
        get() = prefs.getLong("id_token_obtained_at", 0L)
        set(value) = prefs.edit().putLong("id_token_obtained_at", value).apply()

    var idToken: String?
        get() = prefs.getString("id_token", null)
        set(value) {
            prefs.edit().putString("id_token", value).apply()
            if (!value.isNullOrBlank()) tokenObtainedAtMs = System.currentTimeMillis()
        }

    // idToken chi song 1 gio -> coi la cu sau 50 phut, refresh truoc 10 phut
    val isTokenStale: Boolean
        get() {
            val t = tokenObtainedAtMs
            return t == 0L || System.currentTimeMillis() - t > 50 * 60 * 1000L
        }

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    // Co danh dau migrate cache JSON cu (cached_moments/cached_friends)
    // sang Room da chay xong chua (LocketCache.migrateLegacyCacheIfNeeded).
    // Logout xoa het prefs nen co nay reset theo — dung vi Room cung bi
    // xoa het khi logout.
    var roomCacheMigrated: Boolean
        get() = prefs.getBoolean("room_cache_migrated_v1", false)
        set(value) = prefs.edit().putBoolean("room_cache_migrated_v1", value).apply()

    var currentUser: User
        get() {
            val json = prefs.getString("current_user", null)
            return if (json != null) {
                try {
                    gson.fromJson(json, User::class.java)
                } catch (e: Exception) {
                    defaultUser()
                }
            } else {
                defaultUser()
            }
        }
        set(value) {
            prefs.edit().putString("current_user", gson.toJson(value)).apply()
        }

    val isLoggedIn: Boolean
        get() = !idToken.isNullOrBlank()

    fun logout() {
        prefs.edit().clear().apply()
    }

    private fun defaultUser(): User {
        return User(
            uid = "",
            username = "",
            displayName = "",
            profilePictureUrl = "",
            badge = "gold"
        )
    }

    fun getSavedMoments(): List<Moment> {
        val json = prefs.getString("cached_moments", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<Moment>>() {}.type
                val list: List<Moment> = gson.fromJson(json, type)
                return list
            } catch (e: Exception) {
                // ignore
            }
        }
        return emptyList()
    }

    fun saveMoments(moments: List<Moment>) {
        prefs.edit().putString("cached_moments", gson.toJson(moments)).apply()
    }

    fun addMoment(moment: Moment) {
        val list = getSavedMoments().toMutableList()
        list.add(0, moment)
        saveMoments(list)
    }

    fun getFriends(): List<Friend> {
        val json = prefs.getString("cached_friends", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<Friend>>() {}.type
                val list: List<Friend> = gson.fromJson(json, type)
                return list
            } catch (e: Exception) {
                // ignore
            }
        }
        return emptyList()
    }

    fun saveFriends(friends: List<Friend>) {
        prefs.edit().putString("cached_friends", gson.toJson(friends)).apply()
    }
}
