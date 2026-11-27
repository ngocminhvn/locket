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
import com.locket.app.model.RecommendedFriend
import com.locket.app.model.MessageReaction
import com.locket.app.model.Moment
import com.locket.app.model.User
import com.locket.app.rust.RustLocketCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URLEncoder
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
    private data class CachedName(val value: Pair<String, String>, val expiresAt: Long)
    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, CachedName>()
    private val nameResolveLock = kotlinx.coroutines.sync.Mutex()
    private val tokenRefreshLock = kotlinx.coroutines.sync.Mutex()
    private fun nameKey(uid: String) = "${getUid().orEmpty()}:$uid"
    private fun nameNow() = android.os.SystemClock.elapsedRealtime()
    fun seedUserNames(friends: List<Friend>, fresh: Boolean = false) {
        for (friend in friends) {
            if (friend.uid.isBlank() || friend.displayName.isBlank() || friend.displayName == friend.uid) continue
            val key = nameKey(friend.uid)
            val entry = CachedName(friend.displayName to friend.avatarUrl, nameNow() + if (fresh) 900_000L else 120_000L)
            if (fresh) nameCache[key] = entry else nameCache.putIfAbsent(key, entry)
        }
    }
    fun clearUserNameCache() = nameCache.clear()
    private fun cachedUserNames(uids: List<String>): Map<String, Pair<String, String>> =
        uids.distinct().mapNotNull { uid -> nameCache[nameKey(uid)]?.value?.let { uid to it } }.toMap()


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
    suspend fun refreshIdToken(rejectedToken: String? = null): Boolean = tokenRefreshLock.withLock {
        if (!rejectedToken.isNullOrBlank() && getToken() != rejectedToken) return@withLock true
        refreshIdTokenUnlocked()
    }

    private suspend fun refreshIdTokenUnlocked(): Boolean = withContext(Dispatchers.IO) {
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
            val (successful, respStr) = client.newCall(request).execute().use { response ->
                response.isSuccessful to response.body?.string().orEmpty()
            }
            if (!successful) return@withContext false
            val json = gson.fromJson(respStr, JsonObject::class.java)
            val newIdToken = json.get("id_token")?.asString ?: return@withContext false
            val newRefreshToken = json.get("refresh_token")?.asString ?: refreshToken
            onTokensRefreshed(newIdToken, newRefreshToken)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun ensureFreshToken(): Boolean {
        if (!RustLocketCore.isAvailable) {
            if (getToken().isNullOrBlank()) return false
            return if (isTokenStale()) refreshIdToken() else true
        }
        return when (RustLocketCore.tokenAction(!getToken().isNullOrBlank(), isTokenStale(), !getRefreshToken().isNullOrBlank())) {
            RustLocketCore.TokenAction.MISSING -> false
            RustLocketCore.TokenAction.USE_CURRENT -> true
            RustLocketCore.TokenAction.REFRESH -> tokenRefreshLock.withLock {
                when (RustLocketCore.tokenAction(!getToken().isNullOrBlank(), isTokenStale(), !getRefreshToken().isNullOrBlank())) {
                    RustLocketCore.TokenAction.USE_CURRENT -> true
                    RustLocketCore.TokenAction.REFRESH -> refreshIdTokenUnlocked()
                    RustLocketCore.TokenAction.MISSING -> false
                }
            }
        }
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

    // ==========================================
    // HỒ SƠ (PROFILE) + MỜI BẠN BÈ
    // (batch 5 — tat ca da xac minh static trong bundle goc 1.247.3,
    // chua live-verify: xem ghi chu tung ham)
    // ==========================================

    data class AccountDetails(val email: String?, val phone: String?, val birthdayMdd: Int?)

    suspend fun fetchAccountDetails(): Result<AccountDetails> = withContext(Dispatchers.IO) {
        try {
            check(ensureFreshToken()) { "Chưa đăng nhập" }
            val uid = getUid().orEmpty()
            val token = getToken().orEmpty()
            val payload = JsonObject().apply { addProperty("idToken", token) }
            val req = Request.Builder()
                .url("https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=$FIREBASE_API_KEY")
                .addHeader("X-Android-Package", ANDROID_PACKAGE)
                .addHeader("X-Android-Cert", ANDROID_CERT)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            val account = client.newCall(req).execute().use { response ->
                check(response.isSuccessful) { "Không đọc được tài khoản (HTTP ${response.code})" }
                gson.fromJson(response.body?.string(), JsonObject::class.java)
                    .getAsJsonArray("users")?.firstOrNull()?.asJsonObject
                    ?: error("Không tìm thấy tài khoản")
            }
            check(optString(account, "localId") == uid) { "Tài khoản không khớp" }
            var birthday: Int? = null
            var phone = optString(account, "phoneNumber").ifBlank { null }
            var email = optString(account, "email").ifBlank { null }
            val profileRequest = Request.Builder().url("$FIRESTORE_BASE_URL/users/$uid")
                .addHeader("Authorization", "Bearer $token").get().build()
            client.newCall(profileRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val fields = asObj(gson.fromJson(response.body?.string(), JsonObject::class.java), "fields")
                    val birthdayFields = asObj(asObj(asObj(fields, "birthday"), "mapValue"), "fields")
                    val encoded = asObj(birthdayFields, "encoded_mdd")
                    birthday = optString(encoded, "integerValue").ifBlank { optString(encoded, "stringValue") }.toIntOrNull()
                    if (phone == null) phone = extractString(fields, "phone").ifBlank { extractString(fields, "phone_number") }.ifBlank { null }
                    if (email == null) email = extractString(fields, "email").ifBlank { null }
                }
            }
            Result.success(AccountDetails(email, phone, birthday))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun changeAccountEmail(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            check(ensureFreshToken()) { "Chưa đăng nhập" }
            val uid = getUid()
            val payload = JsonObject().apply { add("data", JsonObject().apply { addProperty("email", email.trim()) }) }
            val response = callEndpoint("updateEmailAddress", payload) ?: error("Không nhận được phản hồi")
            if (response.get("status")?.asInt == 601) error("Email đã được sử dụng")
            val customToken = optString(asObj(response, "data"), "token")
            check(response.get("status")?.asInt == 200 && customToken.isNotBlank()) { callableError(response, "Không đổi được email") }
            val auth = exchangeCustomToken(customToken)
            check(auth.success && auth.uid == uid && !auth.idToken.isNullOrBlank() && !auth.refreshToken.isNullOrBlank()) {
                "Máy chủ đã phản hồi đổi email nhưng không xác thực lại được. Hãy kiểm tra tài khoản trước khi thử lại."
            }
            onTokensRefreshed(auth.idToken!!, auth.refreshToken!!)
            // Goc (ChangeEmailSheet, bundle ~1358920, case 385/416): sau
            // signInWithCustomToken, neu currentUser.emailVerified == false
            // thi gui sendEmailVerification(). REST tuong duong: doc
            // emailVerified qua accounts:lookup, neu false thi gui
            // accounts:sendOobCode {requestType: VERIFY_EMAIL}. Chi gui
            // khi xac dinh chua verified; lookup loi thi bo qua vi email
            // da doi xong (khong bao loi gia cho thao tac chinh).
            val verified = lookupEmailVerified(auth.idToken!!)
            if (verified == false) {
                val sent = sendVerificationEmail(auth.idToken!!)
                if (sent.isFailure) error("Đã đổi email nhưng không gửi được email xác minh: ${sent.exceptionOrNull()?.message}")
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    // accounts:lookup doc emailVerified cua idToken moi. Tra null khi
    // khong doc duoc (mang/loi server) — caller tu quyet dinh bo qua.
    private fun lookupEmailVerified(idToken: String): Boolean? {
        return try {
            val payload = JsonObject().apply { addProperty("idToken", idToken) }
            val req = Request.Builder()
                .url("https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=$FIREBASE_API_KEY")
                .addHeader("X-Android-Package", ANDROID_PACKAGE)
                .addHeader("X-Android-Cert", ANDROID_CERT)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val user = gson.fromJson(response.body?.string(), JsonObject::class.java)
                    .getAsJsonArray("users")?.firstOrNull()?.asJsonObject ?: return null
                user.get("emailVerified")?.takeIf { it.isJsonPrimitive }?.asBoolean
            }
        } catch (e: Exception) { null }
    }

    // Gui email xac minh — REST tuong duong cua Firebase Auth SDK
    // currentUser.sendEmailVerification() (bundle ~1358920 case 416).
    // Tra loi server that khi bi tu choi (vd email da verified).
    private fun sendVerificationEmail(idToken: String): Result<Unit> {
        return try {
            val payload = JsonObject().apply {
                addProperty("requestType", "VERIFY_EMAIL")
                addProperty("idToken", idToken)
            }
            val req = Request.Builder()
                .url("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$FIREBASE_API_KEY")
                .addHeader("X-Android-Package", ANDROID_PACKAGE)
                .addHeader("X-Android-Cert", ANDROID_CERT)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { response ->
                val respStr = response.body?.string() ?: ""
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(Exception(parseFirebaseError(respStr)))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun requestEditedPhoneCode(phone: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            check(ensureFreshToken()) { "Chưa đăng nhập" }
            val payload = JsonObject().apply { add("data", JsonObject().apply {
                addProperty("phone", phone)
                addProperty("operation", "change_number")
                addProperty("device_model", android.os.Build.MODEL)
            }) }
            val response = callEndpoint("sendVerificationCode", payload) ?: error("Không nhận được phản hồi")
            check(response.get("status")?.asInt == 200) { callableError(response, "Không gửi được mã xác nhận") }
            val method = optString(asObj(response, "data"), "method")
            check(method.isNotBlank() && method != "firebase") {
                "Máy chủ yêu cầu xác thực SMS qua Firebase SDK; luồng này chưa được hỗ trợ."
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun verifyEditedPhone(phone: String, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            check(ensureFreshToken()) { "Chưa đăng nhập" }
            val payload = JsonObject().apply { add("data", JsonObject().apply {
                addProperty("phone", phone)
                addProperty("verification_code", code)
            }) }
            val response = callEndpoint("verifyEditedPhoneNumber", payload) ?: error("Không nhận được phản hồi")
            val status = response.get("status")?.asInt ?: 0
            val serverError = optString(asObj(response, "data"), "error")
            check(status in 200..299 && serverError.isBlank()) {
                serverError.ifBlank { callableError(response, "Không xác nhận được số điện thoại") }
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun changeBirthday(month: Int, day: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            check(ensureFreshToken()) { "Chưa đăng nhập" }
            java.time.MonthDay.of(month, day)
            val payload = JsonObject().apply { add("data", JsonObject().apply {
                add("birthday", JsonObject().apply { addProperty("encoded_mdd", month * 100 + day) })
            }) }
            val response = callEndpoint("changeProfileInfo", payload)
            check(response != null && ((response.get("status")?.asInt ?: 0) in 200..299 ||
                response.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
                asObj(response, "data")?.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)) {
                callableError(response, "Không đổi được sinh nhật")
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Doi ten hien thi: changeProfileInfo {first_name, last_name} — dung
     * dung 2 field nhu callsite goc duy nhat doi ten (decompiled.js
     * ~1456708: trim() ca 2 truoc khi gui, khong gui field nao khac).
     */
    suspend fun changeProfileInfo(firstName: String, lastName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("first_name", firstName.trim())
                    addProperty("last_name", lastName.trim())
                })
            }
            val resp = callEndpoint("changeProfileInfo", payload)
            if (callableSuccess(resp)) Result.success(true)
            else Result.failure(Exception(callableError(resp, "Lỗi đổi tên hồ sơ")))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Doi anh dai dien (buoc 2): changeProfileInfo {profile_picture_url} —
     * callsite goc ~1336449, sau khi upload xong lay downloadURL.
     */
    suspend fun changeProfilePictureUrl(pictureUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("profile_picture_url", pictureUrl)
                })
            }
            val resp = callEndpoint("changeProfileInfo", payload)
            if (callableSuccess(resp)) Result.success(true)
            else Result.failure(Exception(callableError(resp, "Lỗi đổi ảnh đại diện")))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Tao invite token moi: generateInviteToken {} (khong argument).
     * Response goc: data.invite_token (getInviteTokenFromResponse,
     * decompiled.js ~1230953: r0.data.invite_token; phong thu them dang
     * invite_token truc tiep / data.data.invite_token).
     */
    suspend fun generateInviteToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply { add("data", JsonObject()) }
            val resp = callEndpoint("generateInviteToken", payload)
            val token = resp?.let {
                optString(asObj(it, "data"), "invite_token")
                    .ifBlank { optString(it, "invite_token") }
                    .ifBlank { optString(asObj(asObj(it, "data"), "data"), "invite_token") }
            } ?: ""
            if (token.isNotBlank()) Result.success(token)
            else Result.failure(Exception(callableError(resp, "Không tạo được mã mời")))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Link moi day du cua goc (generateInviteLink, decompiled.js
     * ~1230800-1231260):
     *  1. generateInviteToken -> token
     *  2. deep link = https://locket.camera/invites/{token}?type={platform}
     *     (locketDomain tu app.config extra.firebase.dynamicLinks
     *     .locketDomain = "https://locket.camera"; parseInviteLink doc
     *     token = doan cuoi path, platform = ?type=)
     *  3. createDynamicLink {dynamicLinkInfo} -> short link
     *     (response data.data.link; domainUriPrefix
     *     "https://locket.camera/links", androidPackageName /
     *     iosBundleId = "com.locket.Locket", iosAppStoreId "1600525061").
     * Tra ve short link neu tao duoc; neu createDynamicLink loi thi
     * fallback ve deep link (van mo dung app goc qua parseInviteLink)
     * thay vi bao loi — QR van quet duoc.
     * platform mac dinh "Link" (1 trong invite_link_types cua Remote
     * Config; loai chung cho QR/chia se link).
     */
    suspend fun createInviteLink(platform: String = "Link"): Result<String> = withContext(Dispatchers.IO) {
        val tokenRes = generateInviteToken()
        if (tokenRes.isFailure) {
            return@withContext Result.failure(
                tokenRes.exceptionOrNull() ?: Exception("Không tạo được mã mời")
            )
        }
        val token = tokenRes.getOrThrow()
        val deepLink = "https://locket.camera/invites/$token?type=$platform"
        try {
            val dynamicLinkInfo = JsonObject().apply {
                addProperty("link", deepLink)
                addProperty("domainUriPrefix", "https://locket.camera/links")
                add("androidInfo", JsonObject().apply {
                    addProperty("androidPackageName", "com.locket.Locket")
                })
                add("iosInfo", JsonObject().apply {
                    addProperty("iosBundleId", "com.locket.Locket")
                    addProperty("iosAppStoreId", "1600525061")
                })
            }
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    add("dynamicLinkInfo", dynamicLinkInfo)
                })
            }
            val resp = callEndpoint("createDynamicLink", payload)
            val shortLink = resp?.let {
                optString(asObj(asObj(it, "data"), "data"), "link")
                    .ifBlank { optString(asObj(it, "data"), "link") }
                    .ifBlank { optString(it, "link") }
            } ?: ""
            Result.success(shortLink.ifBlank { deepLink })
        } catch (e: Exception) {
            // createDynamicLink loi (vd 401/App Check) — deep link van dung
            Result.success(deepLink)
        }
    }

    /**
     * Upload anh dai dien goc (uploadProfilePicture, decompiled.js
     * ~1336359-1336449): Storage bucket locket-img, path
     * users/{uid}/public/profile_pic.jpg, putFile -> getDownloadURL ->
     * changeProfileInfo {profile_picture_url}.
     * (Goc dung .jpg hoac .webp tuy dinh dang goc; repo nen anh chon ve
     * JPEG nen luon dung .jpg. Goc xoa file anh cu khi URL doi — repo
     * chua lam buoc xoa cu vi can parse filename tu URL cu.)
     * Dung cung kieu REST Storage nhu uploadMomentThumbnail da co.
     */
    suspend fun uploadProfilePicture(imageBytes: ByteArray): UploadResult = withContext(Dispatchers.IO) {
        val token = getToken()
        val uid = getUid()
        if (token.isNullOrBlank() || uid.isNullOrBlank()) {
            return@withContext UploadResult(false, errorMessage = "Chưa đăng nhập")
        }
        try {
            val encodedPath = "users%2F$uid%2Fpublic%2Fprofile_pic.jpg"
            val uploadUrl = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o?uploadType=media&name=$encodedPath"
            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "image/jpeg")
                .addHeader("Cache-Control", "public, max-age=604800")
                .post(imageBytes.toRequestBody("image/jpeg".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = gson.fromJson(respStr, JsonObject::class.java)
                val downloadTokens = json.get("downloadTokens")?.asString ?: ""
                val publicUrl = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/$encodedPath?alt=media&token=$downloadTokens"
                UploadResult(success = true, downloadUrl = publicUrl)
            } else {
                UploadResult(false, errorMessage = "Upload ảnh đại diện thất bại (HTTP ${response.code}): $respStr")
            }
        } catch (e: Exception) {
            UploadResult(false, errorMessage = e.localizedMessage ?: "Lỗi tải ảnh lên Storage")
        }
    }

    /**
     * Callable ghi (changeProfileInfo/...) tra ve dang khac
     * nhau tuy endpoint: {status: 200} (kieu sendFriendRequest) hoac
     * {success: true} / {data: {success: true}} (kieu changeProfileInfo
     * cua goc, ~1336372: r5.data.success). Thanh cong khi status 2xx,
     * hoac success == true; neu khong co ca 2 thi chi can khong co
     * mang errors.
     */
    private fun callableSuccess(resp: JsonObject?): Boolean {
        if (resp == null) return false
        resp.get("status")?.takeIf { !it.isJsonNull }?.let {
            return try { it.asInt in 200..299 } catch (e: Exception) { false }
        }
        resp.get("success")?.takeIf { !it.isJsonNull }?.let {
            return try { it.asBoolean } catch (e: Exception) { false }
        }
        asObj(resp, "data")?.get("success")?.takeIf { !it.isJsonNull }?.let {
            return try { it.asBoolean } catch (e: Exception) { false }
        }
        val errors = resp.getAsJsonArray("errors")
        return errors == null || errors.size() == 0
    }

    /** Trich loi that tu response callable: errors[0] / error.message / message. */
    private fun callableError(resp: JsonObject?, fallback: String): String {
        resp?.getAsJsonArray("errors")?.firstOrNull()?.let { el ->
            val msg = if (el.isJsonPrimitive) el.asString
            else if (el.isJsonObject) el.asJsonObject?.get("message")?.takeIf { !it.isJsonNull }?.asString
            else null
            if (!msg.isNullOrBlank()) return msg
        }
        asObj(resp, "error")?.get("message")?.takeIf { !it.isJsonNull }?.asString?.let { return it }
        resp?.get("message")?.takeIf { !it.isJsonNull }?.asString?.let { return it }
        return fallback
    }

    suspend fun fetchFriends(userUid: String, cached: List<Friend> = emptyList()): Result<List<Friend>> = withContext(Dispatchers.IO) {
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

            val cachedByUid = cached.associateBy { it.uid }
            val reusable = friendUids.mapNotNull { uid ->
                cachedByUid[uid]?.takeIf {
                    it.displayName.isNotBlank() && it.displayName != uid &&
                        nameCache[nameKey(uid)]?.expiresAt?.let { expiry -> expiry > nameNow() } == true
                }
            }
            val friendsList = reusable.toMutableList()
            val reusedIds = reusable.map { it.uid }.toSet()

            // 1. Try callable fetchUsers (chia nho: server chi nhan toi da 20 uid/call,
            // 21+ tra "Malformed request", da verify live voi 23 ban)
            val allUsers = mutableListOf<com.google.gson.JsonElement>()
            for (batch in friendUids.filterNot { it in reusedIds }.chunked(10)) {
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

            if (getUid() == userUid) seedUserNames(friendsList.filterNot { it.uid in reusedIds }, fresh = true)
            Result.success(friendsList.map { friend ->
                val previous = cachedByUid[friend.uid]
                if (previous != null && (friend.displayName.isBlank() || friend.displayName == friend.uid))
                    friend.copy(displayName = previous.displayName, username = previous.username,
                        avatarUrl = previous.avatarUrl.ifBlank { friend.avatarUrl })
                else friend
            })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
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
        nameResolveLock.withLock {
        val account = getUid()
        val result = cachedUserNames(distinct).toMutableMap()
        val missing = distinct.filter { nameCache[nameKey(it)]?.expiresAt?.let { expiry -> expiry > nameNow() } != true }
        // fetchUsers chi nhan toi da 20 uid/call (21+ server tra "Malformed request", da verify live)
        // -> chia nho moi batch 10 uid cho an toan.
        for (batch in missing.chunked(10)) {
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
                        if (getUid() != account) return@withLock emptyMap<String, Pair<String, String>>()
                        if (displayName != uid) {
                            val pair = displayName to avatar
                            result[uid] = pair
                            nameCache[nameKey(uid)] = CachedName(pair, nameNow() + 900_000L)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // Keep known cached names if refresh fails.
            }
        }
        result
        }
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
     * Goi y ket ban: getFriendRecommendations {dismissed_user_ids, version: "v1"}
     * (bundle 1025704, callsite ~1244458; response validate bang
     * FriendRecommendationsResponseSchema: {version: "v1",
     * recommendations: [{user_uid, mutual_friend_count}]}).
     * DEFENSIVE: probe 27/11 tra HTTP 401 UNAUTHENTICATED du token hop le
     * (nghi can App Check) — moi loi (401, mang, parse, chua dang nhap)
     * deu tra ve danh sach rong de UI an section, khong bao loi, khong
     * hien du lieu gia. Ten/avatar resolve tu fetchUsers nhu ban be
     * (batch 10 uid), fallback doc users/{uid}. Chua live-verify.
     */
    suspend fun getFriendRecommendations(dismissedUserIds: List<String>): Result<List<RecommendedFriend>> = withContext(Dispatchers.IO) {
        try {
            if (!ensureFreshToken()) return@withContext Result.success(emptyList())
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    val arr = JsonArray()
                    dismissedUserIds.forEach { arr.add(it) }
                    add("dismissed_user_ids", arr)
                    addProperty("version", "v1")
                })
            }
            val response = callEndpoint("getFriendRecommendations", payload)
                ?: return@withContext Result.success(emptyList())
            val status = response.get("status")?.asInt
            if (status != null && status != 200) return@withContext Result.success(emptyList())
            // Payload nam o response.data (phong thu them dang data.data.* nhu momentArray).
            val payloadObj: JsonObject? = when {
                response.has("recommendations") -> response
                response.get("data")?.isJsonObject == true && response.getAsJsonObject("data").has("recommendations") -> response.getAsJsonObject("data")
                response.get("data")?.isJsonObject == true && response.getAsJsonObject("data").get("data")?.isJsonObject == true -> response.getAsJsonObject("data").getAsJsonObject("data")
                else -> null
            }
            val version = payloadObj?.get("version")?.takeIf { it.isJsonPrimitive }?.asString
            if (payloadObj == null || (version != null && version != "v1")) {
                return@withContext Result.success(emptyList())
            }
            val recArr = payloadObj.getAsJsonArray("recommendations") ?: JsonArray()
            val recs = mutableListOf<Pair<String, Int>>()
            for (elem in recArr) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val uid = obj.get("user_uid")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                if (uid.isBlank()) continue
                val mutual = obj.get("mutual_friend_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                recs.add(uid to mutual)
            }
            if (recs.isEmpty()) return@withContext Result.success(emptyList())
            val names = resolveUserNames(recs.map { it.first })
            val out = recs.take(10).map { (uid, mutual) ->
                val resolved = names[uid]
                if (resolved != null) {
                    RecommendedFriend(uid = uid, displayName = resolved.first, avatarUrl = resolved.second, mutualFriendCount = mutual)
                } else {
                    val docFriend = fetchUserProfileFromDoc(uid)
                    if (docFriend != null) {
                        RecommendedFriend(uid = uid, username = docFriend.username, displayName = docFriend.displayName, avatarUrl = docFriend.avatarUrl, mutualFriendCount = mutual)
                    } else {
                        RecommendedFriend(uid = uid, displayName = uid, mutualFriendCount = mutual)
                    }
                }
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.success(emptyList())
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
            if (RustLocketCore.isAvailable) {
                val responseBody = callEndpointBytes("getLatestMomentV2", payload)
                    ?: error("Không tải được feed")
                val delta = RustLocketCore.parseFeed(responseBody)
                val nextToken = delta.syncToken?.takeIf { it.isNotBlank() } ?: syncToken
                val names = cachedUserNames(delta.moments.map { it.userUid })
                val resolved = delta.moments.map { moment ->
                    names[moment.userUid]?.let { name ->
                        moment.copy(userName = name.first, userAvatarUrl = name.second)
                    } ?: moment
                }
                return@withContext Result.success(FeedSync(resolved, nextToken))
            }

            // Compatibility fallback for builds where the native libraries have
            // not been produced yet. Keep it until all four packaged ABIs have
            // passed parser parity and device smoke tests.
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
            val names = cachedUserNames(records.map { it.userUid })
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
    suspend fun fetchHistoryPage(pageToken: HistoryCursor? = null, ownOnly: Boolean = false): Result<HistoryPage> = withContext(Dispatchers.IO) {
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
                if (ownOnly) add("where", gson.toJsonTree(mapOf("fieldFilter" to mapOf(
                    "field" to mapOf("fieldPath" to "user"),
                    "op" to "EQUAL",
                    "value" to mapOf("stringValue" to uid)
                ))))
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
            val responseBytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Không tải được lịch sử (HTTP ${response.code})")
                response.body?.bytes() ?: byteArrayOf()
            }
            if (RustLocketCore.isAvailable) {
                val page = RustLocketCore.parseHistory(responseBytes, uid, ownOnly)
                val next = page.nextTimestamp?.let { date ->
                    HistoryCursor(date, requireNotNull(page.nextDocument))
                }
                val names = if (ownOnly) emptyMap() else cachedUserNames(page.moments.map { it.userUid })
                val resolved = page.moments.map { moment ->
                    names[moment.userUid]?.let { name -> moment.copy(userName = name.first, userAvatarUrl = name.second) } ?: moment
                }
                return@withContext Result.success(HistoryPage(resolved, next))
            }
            val rows = gson.fromJson(responseBytes.toString(Charsets.UTF_8), JsonArray::class.java)
                ?: error("Phản hồi lịch sử trống")
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
                if (id.isBlank() || thumb.isBlank() || owner.isBlank() || (ownOnly && owner != uid)) return@mapNotNull null
                Moment(id = id, userUid = owner, userName = "",
                    thumbnailUrl = thumb, videoUrl = extractString(fields, "video_url").ifBlank { null },
                    caption = extractString(fields, "caption"), timestampSeconds = timestamp,
                    timeAgo = formatMomentAge(timestamp), md5 = extractString(fields, "md5"))
            }.orEmpty()
            val names = if (ownOnly) emptyMap() else cachedUserNames(records.map { it.userUid })
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

    /**
     * Luot xem 1 moment: getMomentViews {moment_uid} (bundle 1025858,
     * callsite ~1598928; response data.data.moment_views =
     * [{user, viewed_at}], map qua toMomentView {userId, timestamp}).
     * CHI OWNER xem duoc — probe 27/11 tra 401 "User does not have access
     * to this moment" cho moment cua ban. Tra ve SO LUOT XEM (do dai
     * moment_views, trang dau). end_before (cursor phan trang) khong gui
     * o trang dau, dung nhu lan goi dau cua goc; format timestamp cua
     * end_before chua co response that de xac minh nen chua loop them
     * trang — so hien la so nguoi xem o trang dau.
     * Loi/401 -> Result.failure de UI an chip, khong hien so gia.
     * Chua live-verify cho moment cua minh.
     */
    suspend fun getMomentViews(momentUid: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!ensureFreshToken()) error("Chưa đăng nhập")
            val payload = JsonObject().apply {
                add("data", JsonObject().apply { addProperty("moment_uid", momentUid) })
            }
            val response = callEndpoint("getMomentViews", payload) ?: error("Không nhận được phản hồi")
            val status = response.get("status")?.asInt
            if (status != null && status != 200) error(callableError(response, "Không xem được lượt xem"))
            // moment_views nam o response.data (phong thu them data.data.*).
            val viewsArr: JsonArray? = when {
                response.has("moment_views") -> response.getAsJsonArray("moment_views")
                response.get("data")?.isJsonObject == true && response.getAsJsonObject("data").has("moment_views") -> response.getAsJsonObject("data").getAsJsonArray("moment_views")
                response.get("data")?.isJsonObject == true && response.getAsJsonObject("data").get("data")?.isJsonObject == true -> response.getAsJsonObject("data").getAsJsonObject("data").getAsJsonArray("moment_views")
                else -> null
            }
            if (viewsArr == null) error("Máy chủ trả thiếu moment_views")
            Result.success(viewsArr.size())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 3b. XOA / BAO CAO MOMENT & CHAN / AN BAN
    // ==========================================

    /**
     * Xoa 1 moment cua minh: callable deleteMomentV2
     * {owner_uid, moment_uid, delete_globally} (spec JSON: verified static,
     * request_fields [owner_uid, moment_uid, delete_globally]).
     *
     * Y nghia delete_globally — xac minh tu bundle goc (decompiled.js
     * ~1099160-1099260 + chuoi i18n):
     * - true = xoa cho moi nguoi: "This will permanently delete your photo
     *   from your history and your friends will no longer be able to see
     *   the photo." (delete_photo_globally_confirmation). Goc goi true khi
     *   xoa anh moi nhat (delete_first_photo_alert_title: "Deleting your
     *   photos will now remove them for your friends"), va khi do con goi
     *   setHasDeletedFirstPhoto.
     * - false = chi xoa khoi lich su cua minh: "This will only delete the
     *   photo from your personal history. Anyone else who received it will
     *   still be able to view it." (delete_photo_confirmation).
     * Ca 2 truong hop goc deu dispatch addDeletedMoments truoc (danh dau
     * xoa local, decompiled.js ~1099060) — repo lam tuong duong bang cach
     * bo moment khoi Room cache o ViewModel khi server tra 200.
     */
    suspend fun deleteMoment(ownerUid: String, momentUid: String, deleteGlobally: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("owner_uid", ownerUid)
                    addProperty("moment_uid", momentUid)
                    addProperty("delete_globally", deleteGlobally)
                })
            }
            val resp = callEndpoint("deleteMomentV2", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi xóa khoảnh khắc"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Bao cao 1 moment cua nguoi khac: callable reportMoment
     * {owner_uid, moment_uid} (spec JSON: verified static; goc chi coi la
     * xong khi data.status == 200, decompiled.js ~1099329-1099409, kem
     * logReportedMoment + toast report_photo_toast_success; loi thi log
     * 'reportMoment failed'). Goc co hop thoai xac nhan rieng
     * (report_photo_confirmation_title/body) nen app cung hoi xac nhan
     * truoc khi gui.
     */
    suspend fun reportMoment(ownerUid: String, momentUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("owner_uid", ownerUid)
                    addProperty("moment_uid", momentUid)
                })
            }
            val resp = callEndpoint("reportMoment", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi báo cáo khoảnh khắc"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Chan 1 nguoi: callable blockFriend {user_uid} (spec JSON: verified
     * static, request_fields [user_uid]). Goc co hop thoai xac nhan
     * (block_friend_confirmation_title/body): nguoi bi chan khong nhan
     * tin / xem Locket / gui loi moi ket ban duoc nua, va Locket khong
     * thong bao cho ho. Sau khi chan, server ghi doc vao collection
     * users/{uid}/blocked_users (xem fetchBlockedUsers).
     */
    suspend fun blockFriend(userUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("user_uid", userUid)
                })
            }
            val resp = callEndpoint("blockFriend", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi chặn người dùng"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Bo chan 1 nguoi: callable unblockUser {user_uid} (spec JSON:
     * verified static, request_fields [user_uid]).
     */
    suspend fun unblockUser(userUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("user_uid", userUid)
                })
            }
            val resp = callEndpoint("unblockUser", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi bỏ chặn người dùng"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * An/hien moment cua 1 ban tren feed: callable toggleFriendHidden
     * {user_uid} (spec JSON: verified static, request_fields [user_uid]).
     * Server TU DOI trang thai (khong co param boolean — callsite goc
     * decompiled.js ~1116398 chi gui user_uid, loi thi hien
     * hide_friend_error). Theo chuoi xac nhan goc
     * (hide_friend_confirmation_body/_secondary): an thi khong xem Locket
     * cua nguoi do nua va ho khong nhan Locket moi minh gui; ho khong duoc
     * thong bao, 2 ben van o trong danh sach ban be cua nhau — nen repo
     * chi bo moment cua ho khoi feed hien thi, KHONG xoa khoi friends.
     */
    suspend fun toggleFriendHidden(userUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("user_uid", userUid)
                })
            }
            val resp = callEndpoint("toggleFriendHidden", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi ẩn bạn bè"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Danh sach that cac tai khoan bi chan: list documents Firestore REST
     * `users/{uid}/blocked_users` (database mac dinh, KHONG phai database
     * `locket` nhu history). Goc: helper blockedUsersCollection(uid)
     * (decompiled.js ~950796) + listener onSnapshot tren collection nay
     * (BlockedUsersProvider, ~1246051).
     * Doc that: id = uid nguoi bi chan; field da xac minh trong doc la
     * first_name/last_name (provider chi giu doc co id + ten — xem _temp/
     * _temp2 gan dong 1246000). Ten/avatar uu tien doc field, roi
     * resolveUserNames (fetchUsers batch 10), roi doc users/{uid} nhu
     * fetchFriends, cuoi cung hien uid that. Khong cache Room: REST moi
     * lan mo danh sach la du.
     */
    suspend fun fetchBlockedUsers(): Result<List<com.locket.app.model.BlockedUser>> = withContext(Dispatchers.IO) {
        val token = getToken()
        val uid = getUid()
        if (token.isNullOrBlank() || uid.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Chưa đăng nhập"))
        }

        try {
            val url = "$FIRESTORE_BASE_URL/users/$uid/blocked_users"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(req).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Lỗi Firestore blocked_users: HTTP ${response.code}"))
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val documents = json.getAsJsonArray("documents") ?: JsonArray()

            val blockedUids = mutableListOf<String>()
            val docNames = mutableMapOf<String, Pair<String, String>>()
            for (docElem in documents) {
                val doc = docElem.asJsonObject
                val blockedUid = doc.get("name")?.asString?.substringAfterLast("/") ?: ""
                if (blockedUid.isBlank()) continue
                blockedUids.add(blockedUid)
                // Ten that luu ngay trong doc bi chan (first_name/last_name).
                val fields = doc.getAsJsonObject("fields")
                val first = extractString(fields, "first_name").trim()
                val last = extractString(fields, "last_name").trim()
                val displayName = "$first $last".trim()
                if (displayName.isNotBlank()) {
                    docNames[blockedUid] = displayName to extractString(fields, "profile_picture_url")
                }
            }

            val distinctUids = blockedUids.distinct()
            if (distinctUids.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val names = resolveUserNames(distinctUids)
            val blocked = distinctUids.map { blockedUid ->
                val docName = docNames[blockedUid]
                val resolved = names[blockedUid]
                when {
                    resolved != null -> com.locket.app.model.BlockedUser(
                        uid = blockedUid,
                        displayName = resolved.first,
                        avatarUrl = resolved.second
                    )
                    docName != null -> com.locket.app.model.BlockedUser(
                        uid = blockedUid,
                        displayName = docName.first,
                        avatarUrl = docName.second
                    )
                    else -> {
                        val docFriend = fetchUserProfileFromDoc(blockedUid)
                        if (docFriend != null) {
                            com.locket.app.model.BlockedUser(
                                uid = blockedUid,
                                username = docFriend.username,
                                displayName = docFriend.displayName,
                                avatarUrl = docFriend.avatarUrl
                            )
                        } else {
                            com.locket.app.model.BlockedUser(uid = blockedUid, displayName = blockedUid)
                        }
                    }
                }
            }

            Result.success(blocked)
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
    suspend fun fetchConversations(userUid: String, resolveNames: Boolean = true): Result<List<Conversation>> = withContext(Dispatchers.IO) {
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
                val lastMsgAtMs = if (latestMsg != null) {
                    extractTimestampMs(latestMsg, "created_at")
                } else null
                val latestSender = if (latestMsg != null) extractString(latestMsg, "sender") else ""
                val latestThumb = if (latestMsg != null) extractString(latestMsg, "thumbnail_url") else ""
                val fallbackMsg = extractString(fields, "last_message")
                val fallbackAtMs = extractTimestampMs(fields, "last_message_date")

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
                val lastMessageAtMs = lastMsgAtMs ?: fallbackAtMs
                val timeAgo = lastMessageAtMs?.let { formatTimeAgo(it / 1000) } ?: ""

                // Chua doc: chi khi is_read == false ro rang
                val isRead = extractBool(fields, "is_read")
                val unread = if (isRead == false) 1 else 0

                convList.add(
                    Conversation(
                        id = convId,
                        friend = Friend(uid = otherUid, username = otherUid, displayName = ""),
                        lastMessage = finalMsg,
                        timeAgo = timeAgo,
                        unreadCount = unread,
                        lastMessageAtMs = lastMessageAtMs
                    )
                )
            }

            // Resolve ten that — khong gan uid lam ten nua.
            // Chuoi fallback: (b) fetchUsers (batch 10) -> (c) Firestore
            // users/{uid} doc (xem fetchUserProfileFromDoc) -> (d) UID that.
            // (a) Room friends cache do ViewModel overlay them sau khi goi ham nay.
            val names = if (resolveNames) resolveUserNames(convList.map { it.friend.uid }.distinct())
                else cachedUserNames(convList.map { it.friend.uid })
            val resolved = convList.map { c ->
                val resolvedName = names[c.friend.uid]
                if (resolvedName != null) {
                    c.copy(friend = c.friend.copy(displayName = resolvedName.first, avatarUrl = resolvedName.second))
                } else {
                    val docFriend = if (resolveNames) fetchUserProfileFromDoc(c.friend.uid) else null
                    if (docFriend != null) {
                        c.copy(friend = docFriend)
                    } else {
                        c.copy(friend = c.friend.copy(displayName = c.friend.uid))
                    }
                }
            }

            Result.success(resolved.sortedWith(compareByDescending<Conversation> { it.lastMessageAtMs }.thenBy { it.id }))
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
     *
     * Phan trang tin cu: Firestore REST list tra `nextPageToken` trong
     * response khi con trang sau — truyen lai lam `pageToken` de lay tiep
     * 100 tin cu hon. Token nay la cursor theo document cuoi trang (ben
     * khi tin moi den trong luc polling), khong phai offset. Chon cach nay
     * thay vi cursor created_at nhu startAfter(Timestamp) cua goc (bundle
     * ~1732860: orderBy desc + limit + startAfter(Timestamp.fromMillis(
     * oldestCreatedAt))): REST list khong co startAfter, muon dung kieu
     * goc phai doi sang runQuery/structuredQuery nhu fetchHistoryPage,
     * trong khi nextPageToken cua list da du va dung chuan REST.
     * Het tin cu khi nextPageToken == null.
     */
    data class ChatMessagesPage(val messages: List<ChatMessage>, val nextPageToken: String?)

    suspend fun fetchChatMessages(conversationId: String, pageToken: String? = null, pageSize: Int = 100): Result<ChatMessagesPage> = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Chưa đăng nhập"))
        }
        val myUid = getUid() ?: ""
        try {
            val urlBuilder = StringBuilder("$FIRESTORE_BASE_URL/conversations/$conversationId/messages?pageSize=${pageSize.coerceIn(1, 100)}&orderBy=created_at%20desc")
            if (!pageToken.isNullOrBlank()) {
                urlBuilder.append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
            }
            val req = Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val responseBytes = client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Lỗi Firestore messages: HTTP ${response.code}"))
                }
                response.body?.bytes() ?: byteArrayOf()
            }
            if (RustLocketCore.isAvailable) {
                val page = RustLocketCore.parseChat(responseBytes, conversationId, myUid)
                return@withContext Result.success(ChatMessagesPage(page.messages, page.nextPageToken))
            }
            val json = gson.fromJson(responseBytes.toString(Charsets.UTF_8), JsonObject::class.java)
            val documents = json.getAsJsonArray("documents") ?: JsonArray()
            val messages = mutableListOf<ChatMessage>()
            for (docElem in documents) {
                val parsed = parseChatMessageJson(docElem.asJsonObject, conversationId, myUid)
                if (parsed != null) messages.add(parsed)
            }
            // Sap xep cu -> moi de hien thi; dedupe theo id that (khong sinh id gia)
            val deduped = messages.distinctBy { it.id }.sortedBy { it.createdAtMs }
            val nextPageToken = json.get("nextPageToken")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            Result.success(ChatMessagesPage(deduped, nextPageToken))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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

    /**
     * Go reaction khoi 1 tin nhan chat: callable removeChatMessageReaction
     * {conversation_id, message_id, emoji}. Cung 1 doan code voi
     * sendChatMessageReaction trong bundle goc, chon send/remove theo flag
     * (decompiled.js ~1733500: tim reaction co sender == myUid, bam dung
     * emoji cu thi go, bam emoji khac thi gui moi).
     */
    suspend fun removeChatMessageReaction(conversationId: String, messageId: String, emoji: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("conversation_id", conversationId)
                    addProperty("message_id", messageId)
                    addProperty("emoji", emoji)
                })
            }
            val resp = callEndpoint("removeChatMessageReaction", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi gỡ reaction"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Thu hoi (unsend) 1 tin nhan chat: callable deleteChatMessage
     * {message_uid, conversation_uid}. Goc goi la "unsend" (decompiled.js
     * ~1733206: hop thoai xac nhan unsend_message_confirmation_title/body,
     * nut "unsend", toast unsend_message_toast_error; chi goi status 200
     * moi coi la xong) — thu hoi la thao tac tren tin do chinh minh gui,
     * nen app chi hien muc nay voi tin cua minh.
     */
    suspend fun deleteChatMessage(messageUid: String, conversationUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("message_uid", messageUid)
                    addProperty("conversation_uid", conversationUid)
                })
            }
            val resp = callEndpoint("deleteChatMessage", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi xóa tin nhắn"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Bao cao 1 tin nhan chat cua nguoi khac: callable reportMessage
     * {message_uid, conversation_uid}. Goc co hop thoai xac nhan rieng
     * (report_message_confirmation_title/body, nut "report" destructive,
     * decompiled.js ~1733030), nen app cung hoi xac nhan truoc khi gui.
     */
    suspend fun reportMessage(messageUid: String, conversationUid: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("message_uid", messageUid)
                    addProperty("conversation_uid", conversationUid)
                })
            }
            val resp = callEndpoint("reportMessage", payload)
            if (resp != null && resp.get("status")?.asInt in 200..299) {
                Result.success(true)
            } else {
                val err = resp?.getAsJsonArray("errors")?.firstOrNull()?.asString ?: "Lỗi báo cáo tin nhắn"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendChatMessage(receiverUid: String, message: String, momentUid: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (receiverUid.isBlank() || message.isBlank()) error("Thiếu người nhận hoặc nội dung")
            if (!ensureFreshToken()) error("Phiên đăng nhập hết hạn hoặc không thể làm mới")
            val payload = JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("msg", message)
                    addProperty("receiver_uid", receiverUid)
                    addProperty("client_token", UUID.randomUUID().toString())
                    if (!momentUid.isNullOrBlank()) addProperty("moment_uid", momentUid)
                })
            }
            // Preserve the actual callable error instead of masking every
            // network/server failure behind the same generic toast.
            val bytes = callEndpointBytes("sendChatMessageV2", payload, propagateFailure = true)
                ?: error("Máy chủ không trả dữ liệu")
            val root = gson.fromJson(bytes.toString(Charsets.UTF_8), JsonObject::class.java)
                ?: error("Phản hồi gửi tin nhắn trống")
            val result = asObj(root, "result") ?: root
            val status = optString(result, "status").toIntOrNull()
            val callableError = asObj(root, "error")
            if (callableError == null && status != null && status in 200..299) {
                Result.success(true)
            } else {
                val errors = result.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
                val firstError = errors?.firstOrNull()
                val detail = when {
                    firstError?.isJsonPrimitive == true -> firstError.asString
                    firstError?.isJsonObject == true -> optString(firstError.asJsonObject, "message")
                    else -> ""
                }.ifBlank { optString(result, "message") }
                    .ifBlank { optString(callableError, "message") }
                    .ifBlank { optString(callableError, "status") }
                    .ifBlank { if (status != null) "Máy chủ trả mã $status" else "Phản hồi không có trạng thái xác nhận" }
                Result.failure(Exception(detail))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        isCameraRoll: Boolean = false,
        webp: Boolean = false
    ): UploadResult = withContext(Dispatchers.IO) {
        val token = getToken()
        val uid = getUid() ?: "anonymous"
        val randomId = generateRandomId(20)
        val extension = if (webp) "webp" else "jpg"
        val mime = if (webp) "image/webp" else "image/jpeg"
        val encodedPath = "users%2F$uid%2Fmoments%2Fthumbnails%2F$randomId.$extension"
        val md5Hex = computeMd5(imageBytes, isCameraRoll)

        if (token.isNullOrBlank()) {
            return@withContext UploadResult(
                success = false,
                errorMessage = "Chưa đăng nhập, vui lòng đăng nhập trước khi đăng ảnh"
            )
        }

        try {
            val uploadUrl = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o?uploadType=media&name=$encodedPath"
            val body = imageBytes.toRequestBody(mime.toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", mime)
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
        val bytes = callEndpointBytes(endpoint, payload) ?: return null
        return try {
            val root = gson.fromJson(bytes.toString(Charsets.UTF_8), JsonObject::class.java)
            if (root != null && root.has("result") && root.get("result").isJsonObject) {
                root.getAsJsonObject("result")
            } else root
        } catch (e: Exception) {
            null
        }
    }

    /** Transport only: keep callable response bytes intact for one Rust parse. */
    private suspend fun callEndpointBytes(endpoint: String, payload: JsonObject, propagateFailure: Boolean = false): ByteArray? {
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
        return try {
            withContext(Dispatchers.IO) {
                val usedToken = getToken()
                var resp = client.newCall(buildRequest(usedToken)).execute()
                if (resp.code == 401) {
                    resp.close()
                    // idToken het han (song 1 gio) -> refresh roi thu lai dung 1 lan
                    if (refreshIdToken(usedToken)) {
                        resp = client.newCall(buildRequest(getToken())).execute()
                    } else {
                        if (propagateFailure) error("Phiên đăng nhập hết hạn (HTTP 401)")
                        return@withContext null
                    }
                }
                resp.use { response ->
                    val bytes = response.body?.bytes() ?: byteArrayOf()
                    if (propagateFailure && !response.isSuccessful && bytes.isEmpty()) {
                        error("Máy chủ trả HTTP ${response.code}")
                    }
                    bytes
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (propagateFailure) throw e
            null
        }
    }

    private fun extractString(fields: JsonObject?, fieldName: String): String {
        val v = fields?.getAsJsonObject(fieldName)?.get("stringValue") ?: return ""
        return if (v.isJsonNull) "" else try { v.asString } catch (e: Exception) { "" }
    }

    /** Doc object con cua JSON callable: null khi field vang mat / null / khong phai object. */
    private fun asObj(obj: JsonObject?, fieldName: String): JsonObject? {
        val el = obj?.get(fieldName) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
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
