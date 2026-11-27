package com.locket.app.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.locket.app.LocketApplication
import com.locket.app.api.LocketApiClient
import com.locket.app.model.ChatMessage
import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.model.Moment
import com.locket.app.model.User
import com.locket.app.storage.SessionManager
import com.locket.app.storage.db.LocketCache
import com.locket.app.widget.LocketWidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LocketViewModel(
    val sessionManager: SessionManager,
    val apiClient: LocketApiClient,
    private val cache: LocketCache
) : ViewModel() {

    private val _currentUser = MutableStateFlow<User>(sessionManager.currentUser)
    val currentUser: StateFlow<User> = _currentUser.asStateFlow()

    private val _friends = MutableStateFlow<List<Friend>>(sessionManager.getFriends())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _moments = MutableStateFlow<List<Moment>>(sessionManager.getSavedMoments())
    val moments: StateFlow<List<Moment>> = _moments.asStateFlow()

    private val momentWriteLock = Mutex()
    private var historyCursor: LocketApiClient.HistoryCursor? = null
    private val feedSyncLock = Mutex()
    private var historyJob: Job? = null
    private val _historyLoading = MutableStateFlow(false)
    val historyLoading = _historyLoading.asStateFlow()
    private val _historyHasMore = MutableStateFlow(true)
    val historyHasMore = _historyHasMore.asStateFlow()
    private val _historyError = MutableStateFlow<String?>(null)
    val historyError = _historyError.asStateFlow()

    // URL thumbnail da enqueue prefetch trong phien (tranh goi trung).
    // Chi truy cap tu main thread (LaunchedEffect + viewModelScope mac dinh
    // chay tren Main) nen khong can dong bo them.
    private val prefetchedThumbnails = mutableSetOf<String>()

    // UID moment da goi markMomentAsViewed trong phien (dedupe + cap 100,
    // giong goc: viewedMomentUids, bundle ~1247103 — includes thi bo qua,
    // push roi length > 100 thi slice(-100)). Chi truy cap tu main thread
    // nhu prefetchedThumbnails.
    private val viewedMomentUids = ArrayDeque<String>()

    /**
     * Danh dau 1 moment da xem len server, fire-and-forget: khong chan UI,
     * loi thi bo qua lang. Khong goi lai moment da xem trong phien.
     */
    fun markMomentViewed(momentUid: String) {
        if (momentUid.isBlank() || viewedMomentUids.contains(momentUid)) return
        viewedMomentUids.addLast(momentUid)
        while (viewedMomentUids.size > 100) viewedMomentUids.removeFirst()
        viewModelScope.launch {
            runCatching { apiClient.markMomentAsViewed(momentUid) }
        }
    }

    /** Ngay hom nay dang YYYYMMDD (Gregorian), nhu getGregorianYYYYMMDD goc. */
    private fun todayYyyymmdd(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /**
     * Prefetch thumbnail vao disk cache cua Coil truoc khi user vuot toi.
     * Ly do: anh chi bat dau tai khi page duoc compose (pager giu page ke),
     * nen khong prefetch thi vuot toi moi tai tu mang -> cho ~0,5s tren
     * mang cham. Prefetch chi tai thumbnail tinh; moment video cung chi
     * prefetch thumbnail, KHONG tai file video. AsyncImage sau do doc tu
     * cache nen hien ngay (neu anh da kip tai ve truoc).
     */
    fun prefetchThumbnails(urls: List<String>) {
        val fresh = urls.filter { it.isNotBlank() && prefetchedThumbnails.add(it) }
        if (fresh.isEmpty()) return
        val app = LocketApplication.instance
        val loader = app.imageLoader
        for (url in fresh) {
            loader.enqueue(ImageRequest.Builder(app).data(url).build())
        }
    }

    /**
     * Prefetch thumbnail cua `count` moment ke tiep sau `currentIndex`
     * trong danh sach da sap xep moi-nhat-truoc. currentIndex = -1 (dang o
     * camera) thi prefetch tu moment moi nhat.
     */
    fun prefetchUpcomingMoments(moments: List<Moment>, currentIndex: Int, count: Int = 5) {
        if (moments.isEmpty()) return
        val upcoming = moments.drop((currentIndex + 1).coerceAtLeast(0)).take(count)
        prefetchThumbnails(upcoming.map { it.thumbnailUrl })
    }

    private suspend fun mergeMoments(incoming: List<Moment>) = momentWriteLock.withLock {
        val merged = (_moments.value + incoming).associateBy { it.id }.values
            .sortedByDescending { it.timestampSeconds }
        cache.saveMoments(merged)
        _moments.value = merged
    }

    fun loadOlderMoments() {
        if (!_isLoggedIn.value || _historyLoading.value || !_historyHasMore.value) return
        _historyLoading.value = true
        _historyError.value = null
        val account = sessionManager.currentUser.uid
        historyJob = viewModelScope.launch {
            try {
                val page = apiClient.fetchHistoryPage(historyCursor).getOrThrow()
                if (!_isLoggedIn.value || account != sessionManager.currentUser.uid) return@launch
                val friends = _friends.value.associateBy { it.uid }
                val incoming = page.moments.map { m ->
                    val friend = friends[m.userUid]
                    when {
                        m.userUid == account -> m.copy(userName = _currentUser.value.displayName.ifBlank { "Bạn" },
                            userAvatarUrl = _currentUser.value.profilePictureUrl)
                        friend != null -> m.copy(userName = friend.displayName.ifBlank { m.userName }, userAvatarUrl = friend.avatarUrl.ifBlank { m.userAvatarUrl })
                        else -> m
                    }
                }
                mergeMoments(incoming)
                // Dot anh cu vua ve chinh la cac trang ke tiep user sap vuot
                // toi (dang gan cuoi danh sach) -> prefetch thumbnail ngay de
                // anh kip vao cache truoc khi user vuot toi.
                prefetchThumbnails(incoming.take(5).map { it.thumbnailUrl })
                val next = page.nextPageToken
                if (next != null && next == historyCursor) {
                    _historyError.value = "Máy chủ trả lại cùng con trỏ lịch sử"
                } else {
                    historyCursor = next
                    _historyHasMore.value = next != null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _historyError.value = e.localizedMessage ?: "Không tải được ảnh cũ"
            } finally {
                _historyLoading.value = false
            }
        }
    }

    private val _selectedFriend = MutableStateFlow<Friend?>(null)
    val selectedFriend: StateFlow<Friend?> = _selectedFriend.asStateFlow()

    val filteredMoments: StateFlow<List<Moment>> = combine(_moments, _selectedFriend) { moments, friend ->
        if (friend == null) moments else moments.filter { it.userUid == friend.uid }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _moments.value)

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    // Tin nhan that cua cuoc tro chuyen dang mo (Room cache-first + sync tu Firestore).
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    private var chatMessagesJob: Job? = null

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(sessionManager.isLoggedIn)
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val isLoggedIn: Boolean
        get() = sessionManager.isLoggedIn

    init {
        // Cache-first: chuyen cache JSON cu sang Room 1 lan (neu chua),
        // roi UI doc truc tiep Flow tu Room — mo app la thay du lieu ngay,
        // khong cho mang. Sync chay nen o duoi, xong moi ghi de Room.
        viewModelScope.launch { cache.migrateLegacyCacheIfNeeded() }
        viewModelScope.launch { cache.observeMoments().collect { _moments.value = it } }
        viewModelScope.launch { cache.observeFriends().collect { _friends.value = it } }
        viewModelScope.launch { cache.observeConversations().collect { _conversations.value = it } }
        if (isLoggedIn) {
            viewModelScope.launch {
                apiClient.ensureFreshToken()
                syncAllData()
            }
        }
    }

    fun selectFriend(friend: Friend?) {
        _selectedFriend.value = friend
    }

    // Tra loi rieng 1 ban: bam vao moment cua ban do -> mo camera, gui chi cho ban do.
    // Dat truoc khi mo man hinh camera; camera tu xoa khi roi man hinh / dang xong / huy.
    private val _replyToFriend = MutableStateFlow<Friend?>(null)
    val replyToFriend: StateFlow<Friend?> = _replyToFriend.asStateFlow()

    fun setReplyToFriend(friend: Friend?) {
        _replyToFriend.value = friend
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
            apiClient.ensureFreshToken()
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
                val liveFriends = friendsRes.getOrNull()
                if (liveFriends != null && liveFriends.isNotEmpty()) {
                    currentFriendsList = liveFriends
                    _friends.value = liveFriends
                    cache.saveFriends(liveFriends)
                }
            }

            // 3. Fetch live moments feed
            feedSyncLock.withLock {
            val feedRes = apiClient.fetchMomentsFeed(sessionManager.feedSyncToken)
            val feed = feedRes.getOrNull()
            if (feed != null && _isLoggedIn.value && currentUid == sessionManager.currentUser.uid) {
                val rawMoments = feed.moments
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
                            else -> if (m.userName.isBlank()) m.copy(userName = m.userUid) else m
                        }
                    }
                    mergeMoments(resolved)
                    // Cap nhat Home Screen Widget bang moment moi nhat that
                    LocketWidgetUpdater.updateAll(LocketApplication.instance)
                }
            }

                if (feed != null && _isLoggedIn.value && currentUid == sessionManager.currentUser.uid) {
                    sessionManager.feedSyncToken = feed.syncToken
                }
            }
            if (historyCursor == null && _historyHasMore.value && _historyError.value == null) loadOlderMoments()

            // 4. Fetch live conversations
            if (currentUid.isNotBlank()) {
                refreshConversations()
            }

            _isSyncing.value = false
        }
    }

    /** Chi tai lai danh sach tro chuyen (dung khi quay lai man danh sach). */
    suspend fun refreshConversations() {
        val currentUid = sessionManager.currentUser.uid
        if (currentUid.isBlank()) return
        val convRes = apiClient.fetchConversations(currentUid)
        val liveConvs = convRes.getOrNull() ?: return
        // (a) Room friends cache: chi dung ten/avatar that tu cache.
        // Khong de muc cache ten-UID ghi de ten that vua resolve duoc,
        // va nguoc lai: live chua resolve duoc thi lay ten that tu cache.
        val friendsMap = _friends.value.associateBy { it.uid }
        val resolvedConvs = liveConvs.map { c ->
            c.copy(friend = mergeConversationFriend(c.friend, friendsMap[c.friend.uid]))
        }
        _conversations.value = resolvedConvs
        cache.saveConversations(resolvedConvs)
    }

    /**
     * Gop ten ban be tu 2 nguon (live fetchConversations + Room friends cache):
     * lay displayName/username/avatar that tu bat cu nguon nao co; UID chi
     * dung khi ca 2 nguon deu khong co ten that. Khong bao gio de muc
     * cache ten-UID ghi de ten that vua resolve duoc.
     */
    private fun mergeConversationFriend(live: Friend, cached: Friend?): Friend {
        if (cached == null) return live
        val liveNameReal = live.displayName.isNotBlank() && live.displayName != live.uid
        val cachedNameReal = cached.displayName.isNotBlank() && cached.displayName != cached.uid
        val displayName = when {
            liveNameReal -> live.displayName
            cachedNameReal -> cached.displayName
            else -> live.displayName
        }
        val liveUsernameReal = live.username.isNotBlank() && live.username != live.uid
        val cachedUsernameReal = cached.username.isNotBlank() && cached.username != cached.uid
        val username = when {
            liveUsernameReal -> live.username
            cachedUsernameReal -> cached.username
            else -> live.username
        }
        val avatarUrl = live.avatarUrl.ifBlank { cached.avatarUrl }
        return live.copy(displayName = displayName, username = username, avatarUrl = avatarUrl)
    }

    /**
     * Mo 1 cuoc tro chuyen theo uid ban be: tim id that tu danh sach
     * conversations (Room), hien cache Room ngay, fetch tin that tu
     * Firestore, luu Room, danh dau da doc.
     * Chua co cuoc tro chuyen that (mo tu danh sach ban be) -> khong co id,
     * hien trang trong that; gui tin se tao cuoc tro chuyen tren server.
     */
    fun openChat(friendUid: String) {
        viewModelScope.launch {
            _isChatLoading.value = true
            if (_conversations.value.isEmpty()) {
                refreshConversations()
            }
            val convId = _conversations.value.firstOrNull { it.friend.uid == friendUid }?.id
            _activeConversationId.value = convId
            chatMessagesJob?.cancel()
            if (convId.isNullOrBlank()) {
                _chatMessages.value = emptyList()
                _isChatLoading.value = false
                return@launch
            }
            chatMessagesJob = viewModelScope.launch {
                cache.observeChatMessages(convId).collect { _chatMessages.value = it }
            }
            fetchChatMessagesAndMarkRead(convId)
            _isChatLoading.value = false
        }
    }

    /** Tai lai tin nhan that cua cuoc tro chuyen dang mo + danh dau da doc. */
    fun refreshChat(conversationId: String? = _activeConversationId.value) {
        val convId = conversationId ?: return
        viewModelScope.launch { fetchChatMessagesAndMarkRead(convId) }
    }

    private suspend fun fetchChatMessagesAndMarkRead(convId: String) {
        apiClient.fetchChatMessages(convId).onSuccess { messages ->
            cache.saveChatMessages(messages)
        }
        apiClient.markConversationAsRead(convId).onSuccess {
            val updated = _conversations.value.map {
                if (it.id == convId) it.copy(unreadCount = 0) else it
            }
            _conversations.value = updated
            cache.saveConversations(updated)
        }
    }

    fun postMoment(
        bitmap: Bitmap,
        caption: String,
        recipients: List<String> = emptyList(),
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        videoFile: File? = null
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

                val videoUrl = if (videoFile != null) {
                    val videoRes = apiClient.uploadMomentVideo(videoFile)
                    if (!videoRes.success || videoRes.downloadUrl.isNullOrBlank()) {
                        _isSyncing.value = false
                        onError(videoRes.errorMessage ?: "Lỗi tải video")
                        return@launch
                    }
                    videoRes.downloadUrl
                } else null

                // 2. Call postMomentV2 callable
                // Streak server: chi gui update_streak_for_yyyymmdd khi ngay
                // da luu != hom nay (getStreakUploadPayload, bundle
                // ~1257420-1257480); chi luu ngay moi khi post thanh cong.
                // calculateStreak local van giu lam fallback hien thi.
                val today = todayYyyymmdd()
                val updateStreakFor = today.takeIf { sessionManager.streakLastUpdatedYyyymmdd != it }
                val postRes = apiClient.postMoment(
                    thumbnailUrl = uploadRes.downloadUrl,
                    caption = caption,
                    recipients = recipients,
                    md5 = md5,
                    videoUrl = videoUrl,
                    updateStreakForYyyymmdd = updateStreakFor
                )

                if (postRes.success) {
                    if (updateStreakFor != null) sessionManager.streakLastUpdatedYyyymmdd = updateStreakFor
                    // Fetch server records; never synthesize IDs, timestamps or local feed rows.
                    videoFile?.delete()
                    _isSyncing.value = false
                    syncAllData()
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

    /**
     * Gui tin nhan that qua sendChatMessageV2. Chi bao thanh cong khi server
     * tra 200; tin hien len man hinh la tin that tai lai tu server (co id /
     * timestamp that), KHONG tu them tin local. momentUid: dinh kem tin vao
     * 1 moment (tra loi moment tu Home).
     */
    fun sendChatMessage(
        receiverUid: String,
        message: String,
        momentUid: String? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.sendChatMessage(receiverUid, message, momentUid)
            if (res.isSuccess) {
                refreshConversations()
                // Neu dang mo dung cuoc tro chuyen nay thi tai lai tin that ngay.
                val convId = _conversations.value.firstOrNull { it.friend.uid == receiverUid }?.id
                if (convId != null && convId == _activeConversationId.value) {
                    fetchChatMessagesAndMarkRead(convId)
                }
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi gửi tin nhắn")
            }
        }
    }

    /** Reaction len 1 tin nhan chat that (message id that, khong id gia). */
    fun reactToChatMessage(
        conversationId: String,
        messageId: String,
        emoji: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.sendChatMessageReaction(conversationId, messageId, emoji)
            if (res.isSuccess) {
                fetchChatMessagesAndMarkRead(conversationId)
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi gửi reaction")
            }
        }
    }

    fun removeFriend(targetUid: String, onDone: () -> Unit) {
        viewModelScope.launch {
            apiClient.removeFriend(targetUid)
            val updated = _friends.value.filter { it.uid != targetUid }
            _friends.value = updated
            cache.saveFriends(updated)
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
                sessionManager.savedEmail = email
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
        historyJob?.cancel()
        historyCursor = null
        _historyHasMore.value = true
        _historyError.value = null
        _historyLoading.value = false
        sessionManager.logout()
        viewModelScope.launch { cache.clearAll() }
        chatMessagesJob?.cancel()
        _chatMessages.value = emptyList()
        _activeConversationId.value = null
        _isChatLoading.value = false
        _currentUser.value = sessionManager.currentUser
        _isLoggedIn.value = false
        _friends.value = emptyList()
        _moments.value = emptyList()
        _conversations.value = emptyList()
    }
}
