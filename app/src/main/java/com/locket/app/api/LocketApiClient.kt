package com.locket.app.api

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.locket.app.model.ChatMessage
import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.model.FriendRequest
import com.locket.app.model.MessageReaction
import com.locket.app.model.Moment
import com.locket.app.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
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
                // Doc null-safe: response that co "badge": null — JsonNull.asString()
                // se throw, ca ham fail va Profile trang ten/anh (bug anh chup man hinh).
                val firstName = optString(u, "first_name")
                val lastName = optString(u, "last_name")
                val displayName = "$firstName $lastName".trim().ifBlank { firstName }
                val user = User(
                    uid = userUid,
                    username = optString(u, "username"),
                    displayName = displayName,
                    profilePictureUrl = optString(u, "profile_picture_url"),
                    badge = optString(u, "badge").ifBlank { "gold" }
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

            // 1. Try callable fetchUsers (chia nho: server chi nhan toi da 20 uid/call,
            // 21+ tra "Malformed request", da verify live voi 23 ban)
            val allUsers = mutableListOf<com.google.gson.JsonElement>()
            for (batch in friendUids.chunked(10)) {
                val payload = JsonObject().apply {
                    add("data", JsonObject().apply {
                        add("user_uids", gson.toJsonTree(batch))
                    })
                }
                val usersResp = callEndpoint("fetchUsers", payload)
                val dataElem = usersResp?.get("data")
                val batchArr = when {
                    dataElem?.isJsonArray == true -> dataElem.asJsonArray
                    dataElem?.isJsonObject == true && dataElem.asJsonObject.has("users") -> dataElem.asJsonObject.getAsJsonArray("users")
                    dataElem?.isJsonObject == true && dataElem.asJsonObject.has("data") -> dataElem.asJsonObject.getAsJsonArray("data")
                    else -> null
                }
                if (batchArr != null) allUsers.addAll(batchArr)
            }
            val dataArr: JsonArray? = if (allUsers.isNotEmpty()) {
                JsonArray().apply { allUsers.forEach { add(it) } }
            } else null

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
        // fetchUsers chi nhan toi da 20 uid/call (21+ server tra "Malformed request", da verify live)
        // -> chia nho moi batch 10 uid cho an toan.
        for (batch in distinct.chunked(10)) {
            try {
                val payload = JsonObject().apply {
                    add("data", JsonObject().apply {
                        add("user_uids", gson.toJsonTree(batch))
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
                // batch nay loi thi bo qua, batch khac van chay; phan con lai caller fallback ve uid
            }
        }
        result
    }

    /**
     * Fallback (c): doc Firestore users/{uid} — dung khi fetchUsers khong
     * tra ten cho uid do (giong fallback cua fetchFriends).
     * Field da xac minh trong bundle goc: schema user (zod, ~637800) co
     * uid/first_name/last_name/username/profile_picture_url; ham
     * fetchUserByUidWithCache (~1025296) tra first_name/last_name/
     * profile_picture_url. Tra null neu doc khong ton tai, request loi,
     * hoac khong co ten/username/avatar nao dung duoc.
     */
    private suspend fun fetchUserProfileFromDoc(uid: String): Friend? {
        if (uid.isBlank()) return null
        val token = getToken()
        if (token.isNullOrBlank()) return null
        return try {
            val url = "$FIRESTORE_BASE_URL/users/$uid"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val doc = gson.fromJson(resp.body?.string(), JsonObject::class.java)
            val fields = doc.getAsJsonObject("fields") ?: return null
            val first = extractString(fields, "first_name").trim()
            val last = extractString(fields, "last_name").trim()
            val username = extractString(fields, "username").trim()
            val avatar = extractString(fields, "profile_picture_url")
            val displayName = when {
                first.isNotEmpty() && last.isNotEmpty() -> "$first $last"
                first.isNotEmpty() -> first
                last.isNotEmpty() -> last
                username.isNotEmpty() -> username
                else -> ""
            }
            if (displayName.isBlank() && username.isBlank() && avatar.isBlank()) return null
            Friend(
                uid = uid,
                username = username.ifBlank { uid },
                displayName = displayName.ifBlank { username.ifBlank { uid } },
                avatarUrl = avatar
            )
        } catch (e: Exception) {
            null
        }
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

    /**
     * Inbox loi moi ket ban den: list documents Firestore REST
     * `users/{uid}/incoming_friend_requests` (database mac dinh, KHONG phai
     * database `locket` nhu history).
     * Goc: listener onSnapshot tren collection nay (bundle ~1229541, ham
     * _temp11/_temp13 dong 1227798/1227824): doc chi doc field
     * `requesting_user` = uid nguoi gui; khong doc field ngay gio nao,
     * khong dung id doc — nen ham nay lay uid tu field do (phong thu dung
     * id doc khi field vang mat). Ten/avatar resolve qua resolveUserNames
     * (fetchUsers batch 10) da co; uid nao khong resolve duoc thi fallback
     * doc users/{uid} nhu fetchFriends, cuoi cung hien uid that.
     * Khong cache Room: REST moi lan mo man Ban be la du.
     */
    suspend fun fetchIncomingFriendRequests(): Result<List<FriendRequest>> = withContext(Dispatchers.IO) {
        val token = getToken()
        val uid = getUid()
        if (token.isNullOrBlank() || uid.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Chưa đăng nhập"))
        }

        try {
            val url = "$FIRESTORE_BASE_URL/users/$uid/incoming_friend_requests"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(req).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Lỗi Firestore incoming_friend_requests: HTTP ${response.code}"))
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val documents = json.getAsJsonArray("documents") ?: JsonArray()

            val senderUids = mutableListOf<String>()
            for (docElem in documents) {
                val doc = docElem.asJsonObject
                val fields = doc.getAsJsonObject("fields")
                val senderUid = extractString(fields, "requesting_user")
                    .ifBlank { doc.get("name")?.asString?.substringAfterLast("/") ?: "" }
                if (senderUid.isNotBlank()) {
                    senderUids.add(senderUid)
                }
            }

            val distinctUids = senderUids.distinct()
            if (distinctUids.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val names = resolveUserNames(distinctUids)
            val requests = distinctUids.map { senderUid ->
                val resolved = names[senderUid]
                if (resolved != null) {
                    FriendRequest(uid = senderUid, displayName = resolved.first, avatarUrl = resolved.second)
                } else {
                    val docFriend = fetchUserProfileFromDoc(senderUid)
                    if (docFriend != null) {
                        FriendRequest(
                            uid = senderUid,
                            username = docFriend.username,
                            displayName = docFriend.displayName,
                            avatarUrl = docFriend.avatarUrl
                        )
                    } else {
                        FriendRequest(uid = senderUid, displayName = senderUid)
                    }
                }
            }

            Result.success(requests)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Chap nhan loi moi ket ban: callable acceptFriendRequest {user_uid}
     * (spec JSON: verified static, request_fields [user_uid]; callsite goc
     * ~1587081 va ~1612173, sau do goc logAcceptedFriendRequest).
     */
    suspend fun acceptFriendRequest(userUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("user_uid", userUid)
                })
            }
            val resp = callEndpoint("acceptFriendRequest", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi chấp nhận kết bạn"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Tu choi loi moi ket ban den: callable deleteFriendRequest
     * {user_uid, direction: "incoming"}.
     * Gia tri direction da xac minh tai callsite goc ~1117715 (tu choi loi
     * moi den, kem logRejectedFriendRequest); "outgoing" la huy loi moi da
     * gui (~1612627, kem logRevokedFriendRequest) nen khong dung o day.
     * Spec JSON: verified static, request_fields [user_uid, direction].
     */
    suspend fun declineFriendRequest(userUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("user_uid", userUid)
                    addProperty("direction", "incoming")
                })
            }
            val resp = callEndpoint("deleteFriendRequest", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi từ chối kết bạn"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 3. MOMENTS & FEED (LIVE API)
    // ==========================================

    data class FeedSync(val moments: List<Moment>, val syncToken: String?)

    suspend fun fetchMomentsFeed(syncToken: String? = null): Result<FeedSync> = withContext(Dispatchers.IO) {
        try {
            if (!ensureFreshToken()) error("Chưa đăng nhập")
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    // Goc getLatestMomentV2 (bundle ~1025200, flows 9):
                    // {should_count_missed_moments: true, last_fetch: 1,
                    //  sync_token, include_group_moments}.
                    addProperty("should_count_missed_moments", true)
                    addProperty("last_fetch", 1)
                    addProperty("include_group_moments", true)
                    if (!syncToken.isNullOrBlank()) addProperty("sync_token", syncToken)
                })
            }
            val response = callEndpoint("getLatestMomentV2", payload) ?: error("Không tải được feed")
            val status = response.get("status")?.asInt ?: 0
            val nextToken = optString(response, "sync_token").ifBlank { syncToken }
            if (status == 304) return@withContext Result.success(FeedSync(emptyList(), nextToken))
            if (status != 200) error("Feed HTTP $status")
            val errors = response.get("errors")
            if (errors?.isJsonArray == true && errors.asJsonArray.size() > 0) error("Máy chủ trả lỗi feed")
            val records = momentArray(response)?.mapNotNull { item ->
                if (item.isJsonObject) parseMomentJson(item.asJsonObject) else null
            }.orEmpty()
            val names = resolveUserNames(records.map { it.userUid })
            val resolved = records.map { m ->
                val name = names[m.userUid]
                if (name != null) m.copy(userName = name.first, userAvatarUrl = name.second) else m
            }
            Result.success(FeedSync(resolved, nextToken))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun momentArray(response: JsonObject): JsonArray? {
        val data = response.get("data") ?: return null
        return when {
            data.isJsonArray -> data.asJsonArray
            data.isJsonObject -> data.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        }
    }

    data class HistoryCursor(val date: String, val documentName: String)
    data class HistoryPage(val moments: List<Moment>, val nextPageToken: HistoryCursor?)

    // Query by date with an exact timestamp/document-name boundary; preserve
    // nanoseconds and tie-breaking so equal timestamps never skip records.
    suspend fun fetchHistoryPage(pageToken: HistoryCursor? = null): Result<HistoryPage> = withContext(Dispatchers.IO) {
        try {
            ensureFreshToken()
            val token = getToken()
            val uid = getUid()
            if (token.isNullOrBlank() || uid.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("Chưa đăng nhập"))
            }
            val url = "$FIRESTORE_HISTORY_URL/history/$uid:runQuery"
            val query = JsonObject().apply {
                add("from", gson.toJsonTree(listOf(mapOf("collectionId" to "entries"))))
                add("orderBy", gson.toJsonTree(listOf(
                    mapOf("field" to mapOf("fieldPath" to "date"), "direction" to "DESCENDING"),
                    mapOf("field" to mapOf("fieldPath" to "__name__"), "direction" to "DESCENDING")
                )))
                addProperty("limit", 24)
                if (pageToken != null) add("startAt", JsonObject().apply {
                    addProperty("before", false)
                    add("values", gson.toJsonTree(listOf(
                        mapOf("timestampValue" to pageToken.date),
                        mapOf("referenceValue" to pageToken.documentName)
                    )))
                })
            }
            val payload = JsonObject().apply { add("structuredQuery", query) }
            val request = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            val rows = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Không tải được lịch sử (HTTP ${response.code})")
                gson.fromJson(response.body?.string().orEmpty(), JsonArray::class.java)
                    ?: error("Phản hồi lịch sử trống")
            }
            val documents = rows.mapNotNull { row ->
                row.asJsonObject.get("document")?.takeIf { it.isJsonObject }?.asJsonObject
            }
            val next = if (documents.size < 24) null else {
                val last = documents.last()
                val date = last.getAsJsonObject("fields")?.getAsJsonObject("date")?.let { optString(it, "timestampValue") }.orEmpty()
                val name = optString(last, "name")
                if (date.isBlank() || name.isBlank()) error("Thiếu con trỏ ngày lịch sử")
                HistoryCursor(date, name)
            }
            val records = documents.mapNotNull { doc ->
                val fields = doc.getAsJsonObject("fields") ?: return@mapNotNull null
                val id = extractString(fields, "canonical_uid").ifBlank {
                    optString(doc, "name").substringAfterLast('/')
                }
                val thumb = extractString(fields, "thumbnail_url")
                val owner = extractString(fields, "user")
                val timestamp = extractTimestampMs(fields, "date")?.div(1000)
                    ?: return@mapNotNull null
                if (id.isBlank() || thumb.isBlank() || owner.isBlank()) return@mapNotNull null
                Moment(id = id, userUid = owner, userName = "",
                    thumbnailUrl = thumb, videoUrl = extractString(fields, "video_url").ifBlank { null },
                    caption = extractString(fields, "caption"), timestampSeconds = timestamp,
                    timeAgo = formatMomentAge(timestamp), md5 = extractString(fields, "md5"))
            }.orEmpty()
            val names = resolveUserNames(records.map { it.userUid })
            val resolved = records.map { m ->
                val name = names[m.userUid]
                if (name != null) m.copy(userName = name.first, userAvatarUrl = name.second) else m
            }
            Result.success(HistoryPage(resolved, next))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatMomentAge(timestamp: Long): String {
        val diff = ((System.currentTimeMillis() / 1000) - timestamp).coerceAtLeast(0)
        return when {
            diff < 60 -> "vừa xong"
            diff < 3600 -> "${diff / 60}m"
            diff < 86400 -> "${diff / 3600}h"
            else -> "${diff / 86400}d"
        }
    }

    private fun parseMomentJson(mObj: JsonObject): Moment? {
        val id = optString(mObj, "canonical_uid").ifBlank { optString(mObj, "id") }
        val thumb = optString(mObj, "thumbnail_url")
        val owner = optString(mObj, "user")
        val date = mObj.get("date")?.takeIf { it.isJsonObject }?.asJsonObject
        val timestamp = optString(date, "_seconds").toLongOrNull() ?: return null
        if (id.isBlank() || thumb.isBlank() || owner.isBlank()) return null
        return Moment(id = id, userUid = owner, userName = "", thumbnailUrl = thumb,
            videoUrl = optString(mObj, "video_url").ifBlank { null },
            caption = optString(mObj, "caption"), timestampSeconds = timestamp,
            timeAgo = formatMomentAge(timestamp), md5 = optString(mObj, "md5"))
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

    /**
     * Danh dau 1 moment da xem (callable markMomentAsViewed, spec JSON
     * xac minh LIVE: request {moment_uid}, tra status 200).
     * Goc chi goi khi chua co trong list viewedMomentUids noi bo, cap 100
     * (bundle ~1247103; gate RC androidMarkMomentAsViewed — repo khong
     * doc RC nen bo qua gate). Dedupe + cap nam o ViewModel.
     */
    suspend fun markMomentAsViewed(momentUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("moment_uid", momentUid)
                })
            }
            val resp = callEndpoint("markMomentAsViewed", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                Result.failure(Exception("Lỗi đánh dấu đã xem"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 4. CHAT & CONVERSATIONS (LIVE API)
    // ==========================================

    /**
     * Doc tro chuyen that cua Firestore: users/{uid}/conversations/{convId}.
     * Field da xac minh tu mapper doc->conversation trong bundle goc
     * (orig/evidence_goc/decompiled.js, ham _temp gan dong 1287225):
     * - uid: id cuoc tro chuyen
     * - latest_message: {body, created_at (Timestamp .seconds), sender,
     *   latest_reaction: {sender, emoji, created_at} | null}
     * - is_read: boolean — false = chua doc
     * - members: [uid, uid] — cac thanh vien
     *
     * KHONG co field unread_count nao trong doc (da grep toan bundle) nen
     * unreadCount = 1 khi is_read == false, 0 khi is_read == true.
     * Neu field is_read vang mat thi coi nhu da doc (khong dam bao chua doc
     * khi khong co bang chung).
     * timeAgo tinh tu latest_message.created_at that (truoc day hardcode "1d").
     */
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
                val convId = extractString(fields, "uid")
                    .ifBlank { doc.get("name")?.asString?.substringAfterLast("/") ?: "" }
                if (convId.isBlank()) continue

                // Nguoi doi dien: members[] tru myUid (fallback: field user_id/other_user_id cu)
                val members = extractStringList(fields, "members")
                val otherUid = members.firstOrNull { it != userUid }
                    ?: extractString(fields, "user_id").ifBlank { extractString(fields, "other_user_id") }

                // Tin cuoi: latest_message that; fallback field cu neu server con ghi
                val latestMsg = fields?.getAsJsonObject("latest_message")
                    ?.getAsJsonObject("mapValue")?.getAsJsonObject("fields")
                val lastMsg = if (latestMsg != null) extractString(latestMsg, "body") else ""
                val lastMsgSeconds = if (latestMsg != null) {
                    extractTimestampMs(latestMsg, "created_at")?.let { it / 1000 }
                } else null
                val latestSender = if (latestMsg != null) extractString(latestMsg, "sender") else ""
                val latestThumb = if (latestMsg != null) extractString(latestMsg, "thumbnail_url") else ""
                val fallbackMsg = extractString(fields, "last_message")
                val fallbackSeconds = extractTimestampMs(fields, "last_message_date")?.let { it / 1000 }

                // Preview that, theo thu tu uu tien:
                // 1. body that cua tin cuoi; 2. "Da gui khoanh khac" CHI khi tin cuoi
                // that su la 1 moment (co thumbnail, khong body) do chinh minh gui;
                // 3. field last_message cu (neu server con ghi); 4. de trong.
                // Khong bia chuoi chung cho moi hang khi khong co du lieu that.
                val finalMsg = when {
                    lastMsg.isNotBlank() -> lastMsg
                    latestThumb.isNotBlank() && latestSender == userUid -> "Đã gửi khoảnh khắc"
                    fallbackMsg.isNotBlank() -> fallbackMsg
                    else -> ""
                }
                val finalSeconds = lastMsgSeconds ?: fallbackSeconds
                val timeAgo = finalSeconds?.let { formatTimeAgo(it) } ?: ""

                // Chua doc: chi khi is_read == false ro rang
                val isRead = extractBool(fields, "is_read")
                val unread = if (isRead == false) 1 else 0

                convList.add(
                    Conversation(
                        id = convId,
                        friend = Friend(uid = otherUid, username = otherUid, displayName = ""),
                        lastMessage = finalMsg,
                        timeAgo = timeAgo,
                        unreadCount = unread
                    )
                )
            }

            // Resolve ten that — khong gan uid lam ten nua.
            // Chuoi fallback: (b) fetchUsers (batch 10) -> (c) Firestore
            // users/{uid} doc (xem fetchUserProfileFromDoc) -> (d) UID that.
            // (a) Room friends cache do ViewModel overlay them sau khi goi ham nay.
            val names = resolveUserNames(convList.map { it.friend.uid }.distinct())
            val resolved = convList.map { c ->
                val resolvedName = names[c.friend.uid]
                if (resolvedName != null) {
                    c.copy(friend = c.friend.copy(displayName = resolvedName.first, avatarUrl = resolvedName.second))
                } else {
                    val docFriend = fetchUserProfileFromDoc(c.friend.uid)
                    if (docFriend != null) {
                        c.copy(friend = docFriend)
                    } else {
                        c.copy(friend = c.friend.copy(displayName = c.friend.uid))
                    }
                }
            }

            Result.success(resolved)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lay tin nhan that cua 1 cuoc tro chuyen tu Firestore REST:
     * GET conversations/{conversationId}/messages (pageSize 100), sap xep
     * theo created_at giam dan o server — giong goc: onSnapshot tren
     * messagesCollection orderBy('created_at', 'desc').limit(N) (bundle
     * ~1732028); hien thi thi dao lai cu -> moi o client.
     * Field doc xem parseChatMessageJson.
     */
    suspend fun fetchChatMessages(conversationId: String): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Chưa đăng nhập"))
        }
        val myUid = getUid() ?: ""
        try {
            val url = "$FIRESTORE_BASE_URL/conversations/$conversationId/messages?pageSize=100&orderBy=created_at%20desc"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("Lỗi Firestore messages: HTTP ${resp.code}"))
            }
            val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
            val documents = json.getAsJsonArray("documents") ?: JsonArray()
            val messages = mutableListOf<ChatMessage>()
            for (docElem in documents) {
                val parsed = parseChatMessageJson(docElem.asJsonObject, conversationId, myUid)
                if (parsed != null) messages.add(parsed)
            }
            // Sap xep cu -> moi de hien thi; dedupe theo id that (khong sinh id gia)
            val deduped = messages.distinctBy { it.id }.sortedBy { it.createdAtMs }
            Result.success(deduped)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Danh dau 1 cuoc tro chuyen la da doc: callable markAsRead {conversation_uid}. */
    suspend fun markConversationAsRead(conversationId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("conversation_uid", conversationId)
                })
            }
            val resp = callEndpoint("markAsRead", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi markAsRead"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Reaction len 1 tin nhan chat: callable sendChatMessageReaction {conversation_id, message_id, emoji}. */
    suspend fun sendChatMessageReaction(conversationId: String, messageId: String, emoji: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("conversation_id", conversationId)
                    addProperty("message_id", messageId)
                    addProperty("emoji", emoji)
                })
            }
            val resp = callEndpoint("sendChatMessageReaction", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi gửi reaction"
                Result.failure(Exception(err))
            }
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

    suspend fun uploadMomentVideo(file: File): UploadResult = withContext(Dispatchers.IO) {
        val uid = getUid()
        val token = getToken()
        if (uid.isNullOrBlank() || token.isNullOrBlank() || !file.isFile || file.length() == 0L) {
            return@withContext UploadResult(false, errorMessage = "Video không hợp lệ hoặc chưa đăng nhập")
        }
        val path = "users%2F$uid%2Fmoments%2Fvideos%2F${generateRandomId(20)}.mp4"
        val bucket = "locket-video"
        val endpoint = "https://firebasestorage.googleapis.com/v0/b/$bucket/o"
        try {
            val request = Request.Builder()
                .url("$endpoint?uploadType=media&name=$path")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Cache-Control", "public, max-age=604800")
                .post(file.asRequestBody("video/mp4".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    UploadResult(false, errorMessage = "Lỗi tải video (HTTP ${response.code})")
                } else {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val downloadToken = optString(json, "downloadTokens").substringBefore(',')
                    if (downloadToken.isBlank()) UploadResult(false, errorMessage = "Storage không trả URL video")
                    else UploadResult(true, downloadUrl = "$endpoint/$path?alt=media&token=$downloadToken")
                }
            }
        } catch (e: Exception) {
            UploadResult(false, errorMessage = e.localizedMessage ?: "Lỗi tải video")
        }
    }

    suspend fun postMoment(
        thumbnailUrl: String,
        caption: String = "",
        recipients: List<String> = emptyList(),
        md5: String,
        videoUrl: String? = null,
        // Ngay YYYYMMDD (Gregorian) can bao server cap nhat streak, chi
        // truyen khi streak chua ghi nhan hom nay (getStreakUploadPayload,
        // bundle ~1257420-1257480: last_updated_yyyymmdd !== today ->
        // {update_streak_for_yyyymmdd: today}, nguoc lai khong co field).
        // null = hom nay da ghi nhan roi, khong gui field.
        updateStreakForYyyymmdd: String? = null
    ): PostResult = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isNullOrBlank()) {
            return@withContext PostResult(
                success = false,
                errorMessage = "Chưa đăng nhập"
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
                if (!videoUrl.isNullOrBlank()) addProperty("video_url", videoUrl)
                if (!updateStreakForYyyymmdd.isNullOrBlank()) {
                    addProperty("update_streak_for_yyyymmdd", updateStreakForYyyymmdd)
                }
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
                val root = gson.fromJson(respStr, JsonObject::class.java)
                val json = root.getAsJsonObject("result")
                    ?: return@withContext PostResult(false, errorMessage = "Phản hồi đăng khoảnh khắc không hợp lệ")
                val status = json.get("status")?.asInt ?: 0
                if (status in 200..299 && json.getAsJsonArray("errors")?.size().let { it == null || it == 0 }) {
                    val data = json.get("data")
                    val item = when {
                        data?.isJsonObject == true -> data.asJsonObject
                        data?.isJsonArray == true -> data.asJsonArray.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                        else -> null
                    }
                    val id = optString(item, "canonical_uid").ifBlank { optString(item, "moment_uid") }.ifBlank { optString(item, "id") }
                    PostResult(success = true, momentId = id.ifBlank { null })
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
        val v = fields?.getAsJsonObject(fieldName)?.get("stringValue") ?: return ""
        return if (v.isJsonNull) "" else try { v.asString } catch (e: Exception) { "" }
    }

    /** Doc chuoi tu JSON callable: tra "" khi field vang mat hoac null (JsonNull.asString() se throw). */
    private fun optString(obj: JsonObject?, fieldName: String): String {
        val el = obj?.get(fieldName) ?: return ""
        return if (el.isJsonNull) "" else try { el.asString } catch (e: Exception) { "" }
    }

    /** Doc boolean that cua Firestore REST: fields.<name>.booleanValue (co the vang mat). */
    private fun extractBool(fields: JsonObject?, fieldName: String): Boolean? {
        val el = fields?.getAsJsonObject(fieldName)?.get("booleanValue") ?: return null
        return try { el.asBoolean } catch (e: Exception) { null }
    }

    /**
     * Doc timestamp that cua Firestore REST.
     * Dang chuan: fields.<name>.timestampValue = chuoi RFC3339.
     * Dang phong thu: mapValue {seconds, nanos} (neu server luu dang map).
     * Tra ve epoch millis, hoac null neu khong doc duoc.
     */
    private fun extractTimestampMs(fields: JsonObject?, fieldName: String): Long? {
        val fieldObj = fields?.getAsJsonObject(fieldName) ?: return null
        fieldObj.get("timestampValue")?.takeIf { !it.isJsonNull }?.asString?.let { ts ->
            return try {
                java.time.Instant.parse(ts).toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
        val seconds = fieldObj.getAsJsonObject("mapValue")
            ?.getAsJsonObject("fields")
            ?.getAsJsonObject("seconds")
            ?.get("integerValue")?.asString?.toLongOrNull()
        return seconds?.let { it * 1000 }
    }

    /** Doc mang string that cua Firestore REST: fields.<name>.arrayValue.values[].stringValue. */
    private fun extractStringList(fields: JsonObject?, fieldName: String): List<String> {
        val values = fields?.getAsJsonObject(fieldName)
            ?.getAsJsonObject("arrayValue")?.getAsJsonArray("values") ?: return emptyList()
        return values.mapNotNull { it.asJsonObject?.get("stringValue")?.takeIf { v -> !v.isJsonNull }?.asString }
    }

    /** Cung cong thuc timeAgo dung o parseMomentJson: vua xong / Xm / Xh / Xd. */
    private fun formatTimeAgo(epochSeconds: Long): String {
        val diff = (System.currentTimeMillis() / 1000) - epochSeconds
        return when {
            diff < 60 -> "vừa xong"
            diff < 3600 -> "${diff / 60}m"
            diff < 86400 -> "${diff / 3600}h"
            else -> "${diff / 86400}d"
        }
    }

    /**
     * Parse 1 document tin nhan Firestore REST thanh ChatMessage.
     * Field da xac minh tu ham toMessage trong bundle goc (xem Models.kt).
     * Tra ve null neu doc khong co body/sender/created_at hop le.
     */
    private fun parseChatMessageJson(doc: JsonObject, conversationId: String, myUid: String): ChatMessage? {
        val fields = doc.getAsJsonObject("fields") ?: return null
        val id = doc.get("name")?.asString?.substringAfterLast("/") ?: return null
        val body = extractString(fields, "body")
        val sender = extractString(fields, "sender")
        val createdAtMs = extractTimestampMs(fields, "created_at") ?: return null
        // Moment dinh kem da bi xoa (deleted_moment co gia tri that, khong phai null):
        // bo thumbnail, chi giu text that neu co. Tin khong con noi dung hien thi
        // duoc (khong body, khong thumbnail) thi bo qua.
        val deletedMomentObj = fields.getAsJsonObject("deleted_moment")
        val hasDeletedMoment = deletedMomentObj != null && !deletedMomentObj.has("nullValue")
        val thumbnailUrl = if (hasDeletedMoment) null else extractString(fields, "thumbnail_url").ifBlank { null }
        if (body.isBlank() && thumbnailUrl.isNullOrBlank()) return null

        val reactions = mutableListOf<MessageReaction>()
        val reactionValues = fields.getAsJsonObject("reactions")
            ?.getAsJsonObject("arrayValue")?.getAsJsonArray("values")
        if (reactionValues != null) {
            for (rElem in reactionValues) {
                val rFields = rElem.asJsonObject?.getAsJsonObject("mapValue")?.getAsJsonObject("fields")
                    ?: continue
                val rSender = extractString(rFields, "sender")
                val rEmoji = extractString(rFields, "emoji")
                val rCreated = extractTimestampMs(rFields, "created_at") ?: 0L
                if (rSender.isNotBlank() && rEmoji.isNotBlank()) {
                    reactions.add(MessageReaction(rSender, rEmoji, rCreated))
                }
            }
        }

        return ChatMessage(
            id = id,
            conversationId = conversationId,
            senderUid = sender,
            isMe = sender.isNotBlank() && sender == myUid,
            text = body,
            createdAtMs = createdAtMs,
            clientToken = extractString(fields, "client_token").ifBlank { null },
            replyMomentUid = extractString(fields, "reply_moment").ifBlank { null },
            thumbnailUrl = thumbnailUrl,
            reactions = reactions
        )
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
