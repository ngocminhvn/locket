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

    var idToken: String?
        get() = prefs.getString("id_token", null)
        set(value) = prefs.edit().putString("id_token", value).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

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
