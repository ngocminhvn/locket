package com.locket.app.api

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.model.Moment
import com.locket.app.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class LocketApiClient(
    private val getToken: () -> String?,
    private val getUid: () -> String?,
    private val getRefreshToken: () -> String? = { null },
    private val isTokenStale: () -> Boolean = { false },
    private val onTokensRefreshed: (idToken: String, refreshToken: String) -> Unit = { _, _ -> }
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        const val FIREBASE_API_KEY = "AIzaSyB5dTd-xiLD5dEfWq5OpptnQtnMpE0W0u8"
        const val ANDROID_PACKAGE = "com.locket.Locket"
        const val ANDROID_CERT = "187A27D3D7364A044307F56E66230F973DCCD5B7"
        const val FUNCTIONS_BASE_URL = "https://api.locketcamera.com"
        const val STORAGE_BUCKET = "locket-img"
        const val FIRESTORE_BASE_URL = "https://firestore.googleapis.com/v1/projects/locket-4252a/databases/(default)/documents"
        const val FIRESTORE_HISTORY_URL = "https://firestore.googleapis.com/v1/projects/locket-4252a/databases/locket/documents"
    }

    data class AuthResult(
        val success: Boolean,
        val idToken: String? = null,
        val refreshToken: String? = null,
        val uid: String? = null,
        val email: String? = null,
        val errorMessage: String? = null
    )

    data class UploadResult(
        val success: Boolean,
        val downloadUrl: String? = null,
        val md5: String? = null,
        val errorMessage: String? = null
    )

    data class PostResult(
        val success: Boolean,
        val momentId: String? = null,
        val errorMessage: String? = null
    )

    // ==========================================
    // 1. AUTHENTICATION
    // ==========================================

    suspend fun signInWithEmail(email: String, pass: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$FIREBASE_API_KEY"
            val json = JsonObject().apply {
                addProperty("email", email)
                addProperty("password", pass)
                addProperty("returnSecureToken", true)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Android-Package", ANDROID_PACKAGE)
                .addHeader("X-Android-Cert", ANDROID_CERT)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val respJson = gson.fromJson(respStr, JsonObject::class.java)
                AuthResult(
                    success = true,
                    idToken = respJson.get("idToken")?.asString,
                    refreshToken = respJson.get("refreshToken")?.asString,
                    uid = respJson.get("localId")?.asString,
                    email = email
                )
            } else {
                AuthResult(success = false, errorMessage = parseFirebaseError(respStr))
            }
        } catch (e: Exception) {
            AuthResult(success = false, errorMessage = e.localizedMessage ?: "Lỗi kết nối mạng")
        }
    }

    suspend fun sendVerificationCode(phone: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("phone", phone)
                    addProperty("operation", "sign_in")
                    addProperty("device_model", "Android")
                    addProperty("use_password_if_available", true)
                })
            }
            val resp = callEndpoint("sendVerificationCode", payload)
            if (resp != null && resp.get("status")?.asInt == 200) {
                val data = resp.getAsJsonObject("data")
                val method = data?.get("method")?.asString ?: "code"
                Result.success(method)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Không thể gửi mã xác nhận"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkVerificationCode(phone: String, code: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("phone", phone)
                    addProperty("verification_code", code)
                })
            }
            val resp = callEndpoint("checkVerificationCode", payload)
            val customToken = resp?.getAsJsonObject("data")?.get("token")?.asString
            if (!customToken.isNullOrBlank()) {
                exchangeCustomToken(customToken)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Mã xác nhận không đúng"
                AuthResult(success = false, errorMessage = err)
            }
        } catch (e: Exception) {
            AuthResult(success = false, errorMessage = e.localizedMessage ?: "Lỗi xác nhận mã")
        }
    }

    private fun exchangeCustomToken(customToken: String): AuthResult {
        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=$FIREBASE_API_KEY"
            val json = JsonObject().apply {
                addProperty("token", customToken)
                addProperty("returnSecureToken", true)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Android-Package", ANDROID_PACKAGE)
                .addHeader("X-Android-Cert", ANDROID_CERT)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            return if (response.isSuccessful) {
                val respJson = gson.fromJson(respStr, JsonObject::class.java)
                AuthResult(
                    success = true,
                    idToken = respJson.get("idToken")?.asString,
                    refreshToken = respJson.get("refreshToken")?.asString,
                    uid = respJson.get("localId")?.asString
                )
            } else {
                AuthResult(success = false, errorMessage = parseFirebaseError(respStr))
            }
        } catch (e: Exception) {
            return AuthResult(success = false, errorMessage = e.localizedMessage)
        }
    }

    // idToken chi song 1 gio -> refresh bang refreshToken da luu de app khoi hoi lai account.
    suspend fun refreshIdToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken() ?: return@withContext false
        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            val request = Request.Builder()
                .url("https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY")
                .addHeader("X-Android-Package", ANDROID_PACKAGE)
                .addHeader("X-Android-Cert", ANDROID_CERT)
                .post(formBody)
                .build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return@withContext false
            val json = gson.fromJson(respStr, JsonObject::class.java)
            val newIdToken = json.get("id_token")?.asString ?: return@withContext false
            val newRefreshToken = json.get("refresh_token")?.asString ?: refreshToken
            onTokensRefreshed(newIdToken, newRefreshToken)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun ensureFreshToken(): Boolean {
        if (getToken().isNullOrBlank()) return false
        return if (isTokenStale()) refreshIdToken() else true
    }

    // ==========================================
    // 2. USER PROFILE & FRIENDS (LIVE API)
    // ==========================================

    suspend fun fetchUserProfile(userUid: String): Result<User> = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Chưa đăng nhập"))
        }

        try {
            // First try callable fetchUserV2
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("user_uid", userUid)
                })
            }
            val resp = callEndpoint("fetchUserV2", payload)
            if (resp != null && resp.get("status")?.asInt == 200) {
                val data = resp.getAsJsonObject("data")
                val u = if (data.has("data")) data.getAsJsonObject("data") else data
                val firstName = u?.get("first_name")?.asString ?: ""
                val lastName = u?.get("last_name")?.asString ?: ""
                val displayName = "$firstName $lastName".trim().ifBlank { firstName }
                val user = User(
                    uid = userUid,
                    username = u?.get("username")?.asString ?: "",
                    displayName = displayName,
                    profilePictureUrl = u?.get("profile_picture_url")?.asString ?: "",
                    badge = u?.get("badge")?.asString ?: "gold"
                )
                return@withContext Result.success(user)
            }

            // Fallback: Firestore document users/{uid}
            val firestoreUrl = "$FIRESTORE_BASE_URL/users/$userUid"
            val req = Request.Builder()
                .url(firestoreUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(req).execute()
            if (response.isSuccessful) {
                val doc = gson.fromJson(response.body?.string(), JsonObject::class.java)
                val fields = doc.getAsJsonObject("fields")
                val firstName = extractString(fields, "first_name")
                val lastName = extractString(fields, "last_name")
                val displayName = "$firstName $lastName".trim().ifBlank { firstName }
                val user = User(
                    uid = userUid,
                    username = extractString(fields, "username"),
                    displayName = displayName,
                    email = extractString(fields, "email"),
                    profilePictureUrl = extractString(fields, "profile_picture_url"),
                    badge = extractString(fields, "badge").ifBlank { "gold" }
                )
                Result.success(user)
            } else {
                Result.failure(Exception("HTTP ${response.code} khi lấy hồ sơ người dùng"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchFriends(userUid: String): Result<List<Friend>> = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Chưa đăng nhập"))
        }

        try {
            // Query Firestore collection users/{uid}/friends
            val url = "$FIRESTORE_BASE_URL/users/$userUid/friends"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(req).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Lỗi Firestore friends: HTTP ${response.code}"))
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val documents = json.getAsJsonArray("documents") ?: JsonArray()

            val friendUids = mutableListOf<String>()
            for (docElem in documents) {
                val doc = docElem.asJsonObject
                val docName = doc.get("name")?.asString ?: ""
                val friendUid = docName.substringAfterLast("/")
                if (friendUid.isNotBlank()) {
                    friendUids.add(friendUid)
                }
            }

            if (friendUids.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val friendsList = mutableListOf<Friend>()

            // 1. Try callable fetchUsers
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    add("user_uids", gson.toJsonTree(friendUids))
                })
            }
            val usersResp = callEndpoint("fetchUsers", payload)
            val dataElem = usersResp?.get("data")
            val dataArr = when {
                dataElem?.isJsonArray == true -> dataElem.asJsonArray
                dataElem?.isJsonObject == true && dataElem.asJsonObject.has("users") -> dataElem.asJsonObject.getAsJsonArray("users")
                dataElem?.isJsonObject == true && dataElem.asJsonObject.has("data") -> dataElem.asJsonObject.getAsJsonArray("data")
                else -> null
            }

            if (dataArr != null && dataArr.size() > 0) {
                for (uElem in dataArr) {
                    val u = uElem.asJsonObject
                    val fUid = u.get("uid")?.asString ?: ""
                    val fFirst = if (u.has("first_name") && !u.get("first_name").isJsonNull) u.get("first_name").asString.trim() else ""
                    val fLast = if (u.has("last_name") && !u.get("last_name").isJsonNull) u.get("last_name").asString.trim() else ""
                    val uName = if (u.has("username") && !u.get("username").isJsonNull) u.get("username").asString.trim() else ""
                    val avatar = if (u.has("profile_picture_url") && !u.get("profile_picture_url").isJsonNull) u.get("profile_picture_url").asString else ""

                    val fDisplayName = when {
                        fFirst.isNotEmpty() && fLast.isNotEmpty() -> "$fFirst $fLast"
                        fFirst.isNotEmpty() -> fFirst
                        fLast.isNotEmpty() -> fLast
                        uName.isNotEmpty() -> uName
                        else -> fUid
                    }

                    if (fUid.isNotBlank()) {
                        friendsList.add(
                            Friend(
                                uid = fUid,
                                username = uName.ifBlank { fUid },
                                displayName = fDisplayName,
                                avatarUrl = avatar,
                                isBestFriend = true
                            )
                        )
                    }
                }
            }

            // 2. For any friend not returned by fetchUsers, fetch document users/{uid} individually
            val missingUids = friendUids.filter { uid -> friendsList.none { it.uid == uid } }
            for (mUid in missingUids) {
                try {
                    val userDocUrl = "$FIRESTORE_BASE_URL/users/$mUid"
                    val uReq = Request.Builder()
                        .url(userDocUrl)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                    val uResp = client.newCall(uReq).execute()
                    if (uResp.isSuccessful) {
                        val doc = gson.fromJson(uResp.body?.string(), JsonObject::class.java)
                        val fields = doc.getAsJsonObject("fields")
                        val fFirst = extractString(fields, "first_name").trim()
                        val fLast = extractString(fields, "last_name").trim()
                        val uName = extractString(fields, "username").trim()
                        val avatar = extractString(fields, "profile_picture_url")
                        val fDisplayName = when {
                            fFirst.isNotEmpty() && fLast.isNotEmpty() -> "$fFirst $fLast"
                            fFirst.isNotEmpty() -> fFirst
                            fLast.isNotEmpty() -> fLast
                            uName.isNotEmpty() -> uName
                            else -> mUid
                        }
                        friendsList.add(
                            Friend(
                                uid = mUid,
                                username = uName.ifBlank { mUid },
                                displayName = fDisplayName,
                                avatarUrl = avatar,
                                isBestFriend = true
                            )
                        )
                    } else {
                        friendsList.add(Friend(uid = mUid, username = mUid, displayName = mUid))
                    }
                } catch (e: Exception) {
                    friendsList.add(Friend(uid = mUid, username = mUid, displayName = mUid))
                }
            }

            Result.success(friendsList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolve ten hien thi + avatar cho 1 danh sach uid qua callable fetchUsers.
     * Response that: data = [{uid, first_name, last_name, username, profile_picture_url, badge, temp}].
     * Fallback dung nhu app goc: "first last" -> username -> uid.
     */
    suspend fun resolveUserNames(uids: List<String>): Map<String, Pair<String, String>> = withContext(Dispatchers.IO) {
        val distinct = uids.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<String, Pair<String, String>>()
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    add("user_uids", gson.toJsonTree(distinct))
                })
            }
            val resp = callEndpoint("fetchUsers", payload)
            val dataElem = resp?.get("data")
            val dataArr = when {
                dataElem?.isJsonArray == true -> dataElem.asJsonArray
                dataElem?.isJsonObject == true && dataElem.asJsonObject.has("users") -> dataElem.asJsonObject.getAsJsonArray("users")
                dataElem?.isJsonObject == true && dataElem.asJsonObject.has("data") -> dataElem.asJsonObject.getAsJsonArray("data")
                else -> null
            }
            if (dataArr != null) {
                for (uElem in dataArr) {
                    val u = uElem.asJsonObject
                    val uid = u.get("uid")?.asString ?: continue
                    val first = u.get("first_name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                    val last = u.get("last_name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                    val username = u.get("username")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                    val avatar = u.get("profile_picture_url")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val displayName = when {
                        first.isNotEmpty() && last.isNotEmpty() -> "$first $last"
                        first.isNotEmpty() -> first
                        last.isNotEmpty() -> last
                        username.isNotEmpty() -> username
                        else -> uid
                    }
                    result[uid] = displayName to avatar
                }
            }
        } catch (e: Exception) {
            // tra ve nhung gi resolve duoc; phan con lai caller fallback ve uid
        }
        result
    }

    suspend fun searchUserByUsername(username: String): Result<User?> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("username", username)
                })
            }
            val resp = callEndpoint("getUserByUsername", payload)
            if (resp != null && resp.get("status")?.asInt == 200) {
                val data = resp.getAsJsonObject("data")
                val user = User(
                    uid = data.get("uid")?.asString ?: "",
                    username = data.get("username")?.asString ?: username,
                    displayName = data.get("first_name")?.asString ?: username,
                    profilePictureUrl = data.get("profile_picture_url")?.asString ?: ""
                )
                Result.success(user)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendFriendRequest(targetUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("user_uid", targetUid)
                })
            }
            val resp = callEndpoint("sendFriendRequest", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi gửi kết bạn"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFriend(targetUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("user_uid", targetUid)
                })
            }
            val resp = callEndpoint("removeFriend", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi xóa bạn"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 3. MOMENTS & FEED (LIVE API)
    // ==========================================

    suspend fun fetchMomentsFeed(): Result<List<Moment>> = withContext(Dispatchers.IO) {
        val token = getToken()
        val uid = getUid()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Chưa đăng nhập"))
        }

        val momentsList = mutableListOf<Moment>()

        try {
            // 1. Call getLatestMomentV2
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("should_count_missed_moments", false)
                    addProperty("include_group_moments", true)
                })
            }
            val latestResp = callEndpoint("getLatestMomentV2", payload)
            if (latestResp != null && latestResp.get("status")?.asInt == 200) {
                val dataArr = latestResp.getAsJsonArray("data")
                if (dataArr != null && dataArr.size() > 0) {
                    for (elem in dataArr) {
                        val mObj = elem.asJsonObject
                        momentsList.add(parseMomentJson(mObj))
                    }
                }
            }

            // 2. Call getSampledMoments for more recent moments
            val sampledPayload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("start", 0)
                    addProperty("end", 30)
                    addProperty("count", 30)
                })
            }
            val sampledResp = callEndpoint("getSampledMoments", sampledPayload)
            if (sampledResp != null && sampledResp.get("status")?.asInt == 200) {
                val dataArr = sampledResp.getAsJsonArray("data")
                if (dataArr != null) {
                    for (elem in dataArr) {
                        val mObj = elem.asJsonObject
                        val parsed = parseMomentJson(mObj)
                        if (momentsList.none { it.id == parsed.id }) {
                            momentsList.add(parsed)
                        }
                    }
                }
            }

            // 3. Query Firestore history database: history/{uid}/entries
            if (!uid.isNullOrBlank()) {
                val historyUrl = "$FIRESTORE_HISTORY_URL/history/$uid/entries"
                val req = Request.Builder()
                    .url(historyUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                    val docs = json.getAsJsonArray("documents")
                    if (docs != null) {
                        for (dElem in docs) {
                            val doc = dElem.asJsonObject
                            val fields = doc.getAsJsonObject("fields")
                            val mId = doc.get("name")?.asString?.substringAfterLast("/") ?: ""
                            val thumb = extractString(fields, "thumbnail_url")
                            val cap = extractString(fields, "caption")
                            val userOwner = extractString(fields, "user")
                            if (thumb.isNotBlank() && momentsList.none { it.id == mId }) {
                                momentsList.add(
                                    Moment(
                                        id = mId,
                                        userUid = userOwner,
                                        userName = "",
                                        thumbnailUrl = thumb,
                                        caption = cap,
                                        timeAgo = "kỷ niệm"
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 4. Resolve ten that — getLatestMomentV2 KHONG tra ten, chi co uid (user)
            //    nen goi fetchUsers 1 lan cho toan bo owner uid distinct.
            val myUid = getUid()
            val ownerUids = momentsList.map { it.userUid }.distinct()
            val names = resolveUserNames(ownerUids)
            val resolvedMoments = momentsList.map { m ->
                when {
                    m.userUid == myUid -> m.copy(userName = "Bạn")
                    names.containsKey(m.userUid) -> {
                        val (name, avatar) = names[m.userUid]!!
                        m.copy(userName = name, userAvatarUrl = avatar)
                    }
                    m.userName.isNotBlank() -> m
                    else -> m.copy(userName = m.userUid) // fallback dung nhu app goc: hien uid
                }
            }

            Result.success(resolvedMoments)
        } catch (e: Exception) {
            if (momentsList.isNotEmpty()) {
                Result.success(momentsList)
            } else {
                Result.failure(e)
            }
        }
    }

    private fun parseMomentJson(mObj: JsonObject): Moment {
        val id = mObj.get("canonical_uid")?.asString ?: mObj.get("id")?.asString ?: "m_${System.currentTimeMillis()}"
        val thumb = mObj.get("thumbnail_url")?.asString ?: ""
        val video = mObj.get("video_url")?.asString
        val caption = mObj.get("caption")?.asString ?: ""
        val userUid = mObj.get("user")?.asString ?: ""
        val dateSeconds = mObj.getAsJsonObject("date")?.get("_seconds")?.asLong ?: (System.currentTimeMillis() / 1000)

        // Calculate time ago
        val diff = (System.currentTimeMillis() / 1000) - dateSeconds
        val timeAgo = when {
            diff < 60 -> "vừa xong"
            diff < 3600 -> "${diff / 60}m"
            diff < 86400 -> "${diff / 3600}h"
            else -> "${diff / 86400}d"
        }

        return Moment(
            id = id,
            userUid = userUid,
            userName = "", // placeholder: getLatestMomentV2 KHONG tra ten, chi co uid (user); ten duoc dien o fetchMomentsFeed() qua fetchUsers
            thumbnailUrl = thumb,
            videoUrl = video,
            caption = caption,
            timestampSeconds = dateSeconds,
            timeAgo = timeAgo
        )
    }

    suspend fun reactToMoment(momentUid: String, ownerUid: String, reaction: String, intensity: Int = 1): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("moment_uid", momentUid)
                    addProperty("owner_uid", ownerUid)
                    addProperty("reaction", reaction)
                    addProperty("intensity", intensity)
                })
            }
            val resp = callEndpoint("reactToMoment", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                Result.failure(Exception("Lỗi gửi biểu cảm"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 4. CHAT & CONVERSATIONS (LIVE API)
    // ==========================================

    suspend fun fetchConversations(userUid: String): Result<List<Conversation>> = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Chưa đăng nhập"))
        }

        try {
            val url = "$FIRESTORE_BASE_URL/users/$userUid/conversations"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("Lỗi Firestore conversations: HTTP ${resp.code}"))
            }

            val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
            val documents = json.getAsJsonArray("documents") ?: JsonArray()

            val convList = mutableListOf<Conversation>()
            for (docElem in documents) {
                val doc = docElem.asJsonObject
                val fields = doc.getAsJsonObject("fields")
                val convId = doc.get("name")?.asString?.substringAfterLast("/") ?: ""
                val otherUid = extractString(fields, "user_id").ifBlank { extractString(fields, "other_user_id") }
                val lastMsg = extractString(fields, "last_message").ifBlank { "Đã gửi khoảnh khắc" }
                val timeStr = extractString(fields, "last_message_date")

                convList.add(
                    Conversation(
                        id = convId,
                        friend = Friend(uid = otherUid, username = otherUid, displayName = ""),
                        lastMessage = lastMsg,
                        timeAgo = "1d"
                    )
                )
            }

            // Resolve ten that — khong gan uid lam ten nua
            val names = resolveUserNames(convList.map { it.friend.uid }.distinct())
            val resolved = convList.map { c ->
                val resolvedName = names[c.friend.uid]
                if (resolvedName != null) {
                    c.copy(friend = c.friend.copy(displayName = resolvedName.first, avatarUrl = resolvedName.second))
                } else {
                    c.copy(friend = c.friend.copy(displayName = c.friend.uid))
                }
            }

            Result.success(resolved)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendChatMessage(receiverUid: String, message: String, momentUid: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("msg", message)
                    addProperty("receiver_uid", receiverUid)
                    addProperty("client_token", UUID.randomUUID().toString())
                    if (momentUid != null) {
                        addProperty("moment_uid", momentUid)
                    }
                })
            }
            val resp = callEndpoint("sendChatMessageV2", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                Result.failure(Exception("Lỗi gửi tin nhắn"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 5. UPLOAD & POST MOMENT
    // ==========================================

    fun computeMd5(bytes: ByteArray, isCameraRoll: Boolean = false): String {
        var base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (isCameraRoll) {
            base64 += "_${System.currentTimeMillis()}"
        }
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(base64.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun uploadMomentThumbnail(
        imageBytes: ByteArray,
        isCameraRoll: Boolean = false
    ): UploadResult = withContext(Dispatchers.IO) {
        val token = getToken()
        val uid = getUid() ?: "anonymous"
        val randomId = generateRandomId(20)
        val encodedPath = "users%2F$uid%2Fmoments%2Fthumbnails%2F$randomId.jpg"
        val md5Hex = computeMd5(imageBytes, isCameraRoll)

        if (token.isNullOrBlank()) {
            return@withContext UploadResult(
                success = false,
                errorMessage = "Chưa đăng nhập, vui lòng đăng nhập trước khi đăng ảnh"
            )
        }

        try {
            val uploadUrl = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o?uploadType=media&name=$encodedPath"
            val body = imageBytes.toRequestBody("image/jpeg".toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "image/jpeg")
                .addHeader("Cache-Control", "public, max-age=604800")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = gson.fromJson(respStr, JsonObject::class.java)
                val downloadTokens = json.get("downloadTokens")?.asString ?: ""
                val publicUrl = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/$encodedPath?alt=media&token=$downloadTokens"
                UploadResult(
                    success = true,
                    downloadUrl = publicUrl,
                    md5 = md5Hex
                )
            } else {
                UploadResult(
                    success = false,
                    errorMessage = "Upload Storage thất bại (HTTP ${response.code}): $respStr"
                )
            }
        } catch (e: Exception) {
            UploadResult(success = false, errorMessage = e.localizedMessage ?: "Lỗi tải ảnh lên Storage")
        }
    }

    suspend fun postMoment(
        thumbnailUrl: String,
        caption: String = "",
        recipients: List<String> = emptyList(),
        md5: String
    ): PostResult = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isNullOrBlank()) {
            return@withContext PostResult(
                success = true,
                momentId = "local_${System.currentTimeMillis()}"
            )
        }

        try {
            val url = "$FUNCTIONS_BASE_URL/postMomentV2"
            val innerData = JsonObject().apply {
                addProperty("thumbnail_url", thumbnailUrl)
                addProperty("caption", caption)
                add("recipients", gson.toJsonTree(recipients))
                add("overlays", gson.toJsonTree(emptyList<String>()))
                addProperty("md5", md5)
            }
            val payload = JsonObject().apply {
                add("data", innerData)
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = gson.fromJson(respStr, JsonObject::class.java)
                val status = json.get("status")?.asInt ?: 200
                if (status in 200..299) {
                    PostResult(success = true, momentId = "moment_${System.currentTimeMillis()}")
                } else {
                    val err = json.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi máy chủ ($status)"
                    PostResult(success = false, errorMessage = err)
                }
            } else {
                PostResult(success = false, errorMessage = "HTTP ${response.code}: $respStr")
            }
        } catch (e: Exception) {
            PostResult(success = false, errorMessage = e.localizedMessage ?: "Lỗi gửi moment")
        }
    }

    // ==========================================
    // UTILITY HELPERS
    // ==========================================

    private suspend fun callEndpoint(endpoint: String, payload: JsonObject): JsonObject? {
        val url = "$FUNCTIONS_BASE_URL/$endpoint"
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        fun buildRequest(token: String?): Request {
            val builder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body)
            if (!token.isNullOrBlank()) builder.addHeader("Authorization", "Bearer $token")
            return builder.build()
        }
        fun parseResponse(resp: Response): JsonObject? {
            val str = resp.body?.string() ?: ""
            val root = gson.fromJson(str, JsonObject::class.java)
            return if (root != null && root.has("result") && root.get("result").isJsonObject) {
                root.getAsJsonObject("result")
            } else root
        }
        return try {
            withContext(Dispatchers.IO) {
                var resp = client.newCall(buildRequest(getToken())).execute()
                if (resp.code == 401) {
                    resp.close()
                    // idToken het han (song 1 gio) -> refresh roi thu lai dung 1 lan
                    if (refreshIdToken()) {
                        resp = client.newCall(buildRequest(getToken())).execute()
                    } else {
                        return@withContext null
                    }
                }
                parseResponse(resp)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractString(fields: JsonObject?, fieldName: String): String {
        return fields?.getAsJsonObject(fieldName)?.get("stringValue")?.asString ?: ""
    }

    private fun parseFirebaseError(respStr: String): String {
        return try {
            val json = gson.fromJson(respStr, JsonObject::class.java)
            json.getAsJsonObject("error")?.get("message")?.asString ?: respStr
        } catch (e: Exception) {
            respStr
        }
    }

    private fun generateRandomId(length: Int = 20): String {
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { allowedChars[Random.nextInt(allowedChars.length)] }
            .joinToString("")
    }
}
