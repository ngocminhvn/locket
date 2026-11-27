package com.locket.app.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locket.app.api.LocketApiClient
import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.model.Moment
import com.locket.app.model.User
import com.locket.app.storage.SessionManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Calendar

class LocketViewModel(
    val sessionManager: SessionManager,
    val apiClient: LocketApiClient
) : ViewModel() {

    private val _currentUser = MutableStateFlow<User>(sessionManager.currentUser)
    val currentUser: StateFlow<User> = _currentUser.asStateFlow()

    private val _friends = MutableStateFlow<List<Friend>>(sessionManager.getFriends())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _moments = MutableStateFlow<List<Moment>>(sessionManager.getSavedMoments())
    val moments: StateFlow<List<Moment>> = _moments.asStateFlow()

    private val _selectedFriend = MutableStateFlow<Friend?>(null)
    val selectedFriend: StateFlow<Friend?> = _selectedFriend.asStateFlow()

    val filteredMoments: StateFlow<List<Moment>> = combine(_moments, _selectedFriend) { moments, friend ->
        if (friend == null) moments else moments.filter { it.userUid == friend.uid }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _moments.value)

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(sessionManager.isLoggedIn)
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val isLoggedIn: Boolean
        get() = sessionManager.isLoggedIn

    init {
        if (isLoggedIn) {
            syncAllData()
        }
    }

    fun selectFriend(friend: Friend?) {
        _selectedFriend.value = friend
    }

    fun calculateStreak(momentsList: List<Moment>): Int {
        if (momentsList.isEmpty()) return 0
        val cal = Calendar.getInstance()
        val days = momentsList.map { m ->
            val mCal = Calendar.getInstance().apply { timeInMillis = m.timestampSeconds * 1000 }
            mCal.get(Calendar.YEAR) * 1000 + mCal.get(Calendar.DAY_OF_YEAR)
        }.toSet()

        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)

        var streak = 0
        var currentDay = if (days.contains(today)) today else if (days.contains(yesterday)) yesterday else null
        val testCal = Calendar.getInstance()
        if (currentDay == yesterday) {
            testCal.add(Calendar.DAY_OF_YEAR, -1)
        }
        while (currentDay != null && days.contains(currentDay)) {
            streak++
            testCal.add(Calendar.DAY_OF_YEAR, -1)
            currentDay = testCal.get(Calendar.YEAR) * 1000 + testCal.get(Calendar.DAY_OF_YEAR)
        }
        return streak
    }

    fun syncAllData() {
        viewModelScope.launch {
            _isSyncing.value = true
            val currentUid = sessionManager.currentUser.uid

            // 1. Fetch user profile
            if (currentUid.isNotBlank()) {
                val userRes = apiClient.fetchUserProfile(currentUid)
                userRes.onSuccess { user ->
                    _currentUser.value = user
                    sessionManager.currentUser = user
                }
            }

            // 2. Fetch live friends
            var currentFriendsList = _friends.value
            if (currentUid.isNotBlank()) {
                val friendsRes = apiClient.fetchFriends(currentUid)
                friendsRes.onSuccess { liveFriends ->
                    if (liveFriends.isNotEmpty()) {
                        currentFriendsList = liveFriends
                        _friends.value = liveFriends
                        sessionManager.saveFriends(liveFriends)
                    }
                }
            }

            // 3. Fetch live moments feed
            val feedRes = apiClient.fetchMomentsFeed()
            feedRes.onSuccess { rawMoments ->
                if (rawMoments.isNotEmpty()) {
                    val friendsMap = currentFriendsList.associateBy { it.uid }
                    val myUid = _currentUser.value.uid
                    val resolved = rawMoments.map { m ->
                        when {
                            m.userUid == myUid -> m.copy(
                                userName = _currentUser.value.displayName.ifBlank { "Bạn" },
                                userAvatarUrl = _currentUser.value.profilePictureUrl
                            )
                            friendsMap.containsKey(m.userUid) -> {
                                val f = friendsMap[m.userUid]!!
                                m.copy(
                                    userName = f.displayName,
                                    userAvatarUrl = f.avatarUrl
                                )
                            }
                            else -> m
                        }
                    }
                    _moments.value = resolved
                    sessionManager.saveMoments(resolved)
                }
            }

            // 4. Fetch live conversations
            if (currentUid.isNotBlank()) {
                val convRes = apiClient.fetchConversations(currentUid)
                convRes.onSuccess { liveConvs ->
                    val friendsMap = currentFriendsList.associateBy { it.uid }
                    val resolvedConvs = liveConvs.map { c ->
                        val f = friendsMap[c.friend.uid]
                        if (f != null) c.copy(friend = f) else c
                    }
                    _conversations.value = resolvedConvs
                }
            }

            _isSyncing.value = false
        }
    }

    fun postMoment(
        bitmap: Bitmap,
        caption: String,
        recipients: List<String> = emptyList(),
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isSyncing.value = true
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val bytes = stream.toByteArray()

            // 1. Upload to Firebase Storage
            val uploadRes = apiClient.uploadMomentThumbnail(bytes)
            if (uploadRes.success && uploadRes.downloadUrl != null) {
                val md5 = uploadRes.md5 ?: apiClient.computeMd5(bytes)

                // 2. Call postMomentV2 callable
                val postRes = apiClient.postMoment(
                    thumbnailUrl = uploadRes.downloadUrl,
                    caption = caption,
                    recipients = recipients,
                    md5 = md5
                )

                if (postRes.success) {
                    val newMoment = Moment(
                        id = postRes.momentId ?: "m_${System.currentTimeMillis()}",
                        userUid = sessionManager.currentUser.uid,
                        userName = sessionManager.currentUser.displayName.ifBlank { "Bạn" },
                        thumbnailUrl = uploadRes.downloadUrl,
                        caption = caption,
                        timeAgo = "vừa xong",
                        isLocal = true
                    )
                    val updated = _moments.value.toMutableList()
                    updated.add(0, newMoment)
                    _moments.value = updated
                    sessionManager.saveMoments(updated)
                    _isSyncing.value = false
                    onSuccess()
                } else {
                    _isSyncing.value = false
                    onError(postRes.errorMessage ?: "Lỗi đăng khoảnh khắc")
                }
            } else {
                _isSyncing.value = false
                onError(uploadRes.errorMessage ?: "Lỗi tải ảnh lên Storage")
            }
        }
    }

    fun reactToMoment(momentId: String, ownerUid: String, reaction: String) {
        viewModelScope.launch {
            apiClient.reactToMoment(momentId, ownerUid, reaction)
        }
    }

    fun sendChatMessage(receiverUid: String, message: String) {
        viewModelScope.launch {
            apiClient.sendChatMessage(receiverUid, message)
            syncAllData()
        }
    }

    fun removeFriend(targetUid: String, onDone: () -> Unit) {
        viewModelScope.launch {
            apiClient.removeFriend(targetUid)
            val updated = _friends.value.filter { it.uid != targetUid }
            _friends.value = updated
            sessionManager.saveFriends(updated)
            onDone()
        }
    }

    fun loginWithEmail(email: String, pass: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _isSyncing.value = true
            val res = apiClient.signInWithEmail(email, pass)
            _isSyncing.value = false
            if (res.success && res.idToken != null) {
                sessionManager.idToken = res.idToken
                sessionManager.refreshToken = res.refreshToken
                sessionManager.currentUser = sessionManager.currentUser.copy(
                    uid = res.uid ?: "user_me",
                    email = email
                )
                _currentUser.value = sessionManager.currentUser
                _isLoggedIn.value = true
                syncAllData()
                onSuccess()
            } else {
                onError(res.errorMessage ?: "Đăng nhập thất bại")
            }
        }
    }

    fun sendPhoneOtp(phone: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _isSyncing.value = true
            val res = apiClient.sendVerificationCode(phone)
            _isSyncing.value = false
            if (res.isSuccess) {
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi gửi mã OTP")
            }
        }
    }

    fun verifyPhoneOtp(phone: String, code: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _isSyncing.value = true
            val res = apiClient.checkVerificationCode(phone, code)
            _isSyncing.value = false
            if (res.success && res.idToken != null) {
                sessionManager.idToken = res.idToken
                sessionManager.refreshToken = res.refreshToken
                sessionManager.currentUser = sessionManager.currentUser.copy(
                    uid = res.uid ?: "user_me"
                )
                _currentUser.value = sessionManager.currentUser
                _isLoggedIn.value = true
                syncAllData()
                onSuccess()
            } else {
                onError(res.errorMessage ?: "Mã xác nhận không hợp lệ")
            }
        }
    }

    fun loginWithDirectToken(idToken: String, uid: String, onSuccess: () -> Unit) {
        sessionManager.idToken = idToken
        sessionManager.currentUser = sessionManager.currentUser.copy(uid = uid)
        _currentUser.value = sessionManager.currentUser
        _isLoggedIn.value = true
        syncAllData()
        onSuccess()
    }

    fun logout() {
        sessionManager.logout()
        _currentUser.value = sessionManager.currentUser
        _isLoggedIn.value = false
        _friends.value = emptyList()
        _moments.value = emptyList()
        _conversations.value = emptyList()
    }
}
