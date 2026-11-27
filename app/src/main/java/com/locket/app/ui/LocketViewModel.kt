package com.locket.app.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.locket.app.LocketApplication
import com.locket.app.api.LocketApiClient
import com.locket.app.model.BlockedUser
import com.locket.app.model.ChatMessage
import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.model.FriendRequest
import com.locket.app.model.Moment
import com.locket.app.model.User
import com.locket.app.storage.SessionManager
import com.locket.app.storage.db.LocketCache
import com.locket.app.widget.LocketWidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    // Loi moi ket ban den (inbox): REST moi lan mo man Ban be, khong cache
    // Room. Nguon that: Firestore users/{uid}/incoming_friend_requests.
    private val _incomingFriendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val incomingFriendRequests: StateFlow<List<FriendRequest>> = _incomingFriendRequests.asStateFlow()

    // Danh sach that cac tai khoan bi chan: REST moi lan mo man Profile,
    // khong cache Room. Nguon that: Firestore users/{uid}/blocked_users.
    private val _blockedUsers = MutableStateFlow<List<BlockedUser>>(emptyList())
    val blockedUsers: StateFlow<List<BlockedUser>> = _blockedUsers.asStateFlow()

    // Tombstone trong phien (khong luu DB): id moment da xoa + uid ban da
    // an — mergeMoments loc bo chung de moment vua xoa/an khong hien lai
    // khi sync/feed/history tai lai truoc khi server lan xong. Xoa la vinh
    // vien tren server; an thi toggle duoc (mo lai se go khoi set khi co
    // thao tac bo an — hien chua co UI bo an).
    private val deletedMomentIds = mutableSetOf<String>()
    private val hiddenFriendUids = mutableSetOf<String>()

    private val _moments = MutableStateFlow<List<Moment>>(sessionManager.getSavedMoments())
    val moments: StateFlow<List<Moment>> = _moments.asStateFlow()

    private val momentWriteLock = Mutex()
    private var historyCursor: LocketApiClient.HistoryCursor? = null
    private val feedSyncLock = Mutex()
    private var historyJob: Job? = null
    private var ownHistoryCursor: LocketApiClient.HistoryCursor? = null
    private var ownHistoryJob: Job? = null
    private val _ownHistoryLoading = MutableStateFlow(false)
    val ownHistoryLoading = _ownHistoryLoading.asStateFlow()
    private val _ownHistoryHasMore = MutableStateFlow(true)
    val ownHistoryHasMore = _ownHistoryHasMore.asStateFlow()
    private val _ownHistoryError = MutableStateFlow<String?>(null)
    val ownHistoryError = _ownHistoryError.asStateFlow()
    private val _ownHistoryCoveredThrough = MutableStateFlow<Long?>(null)
    val ownHistoryCoveredThrough = _ownHistoryCoveredThrough.asStateFlow()

    // Independent cursor: calendar queries own posts only and never advance
    // the friends-feed pagination cursor.
    fun loadOwnHistory() {
        if (!_isLoggedIn.value || _ownHistoryLoading.value || !_ownHistoryHasMore.value) return
        _ownHistoryLoading.value = true
        _ownHistoryError.value = null
        val account = sessionManager.currentUser.uid
        ownHistoryJob = viewModelScope.launch {
            try {
                val page = apiClient.fetchHistoryPage(ownHistoryCursor, ownOnly = true).getOrThrow()
                if (!_isLoggedIn.value || account != sessionManager.currentUser.uid) return@launch
                val own = page.moments.filter { it.userUid == account }.map {
                    it.copy(userName = _currentUser.value.displayName.ifBlank { "Bạn" },
                        userAvatarUrl = _currentUser.value.profilePictureUrl)
                }
                mergeMoments(own)
                val next = page.nextPageToken
                if (next != null && next == ownHistoryCursor) error("Máy chủ trả lại cùng con trỏ")
                ownHistoryCursor = next
                _ownHistoryCoveredThrough.value = next?.let { java.time.Instant.parse(it.date).epochSecond }
                _ownHistoryHasMore.value = next != null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ownHistoryError.value = e.localizedMessage ?: "Không tải được kỷ niệm"
            } finally {
                _ownHistoryLoading.value = false
            }
        }
    }

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
        val merged = (_moments.value + incoming)
            .filter { it.id !in deletedMomentIds && it.userUid !in hiddenFriendUids }
            .associateBy { it.id }.values
            .sortedByDescending { it.timestampSeconds }
        cache.saveMoments(merged)
        _moments.value = merged
    }

    /**
     * Bo moment khoi danh sach hien thi + Room cache (dung sau khi xoa /
     * chan / an thanh cong tren server). Chay duoi momentWriteLock nhu
     * mergeMoments de khong mat update khi sync chay song song.
     */
    private suspend fun removeMomentsFromCache(predicate: (Moment) -> Boolean) = momentWriteLock.withLock {
        val updated = _moments.value.filterNot(predicate)
        _moments.value = updated
        cache.saveMoments(updated)
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

    // Phan trang tin cu trong chat dang mo: token trang ke tiep cua
    // Firestore REST list (nextPageToken, null = het tin cu). Chi dat lai
    // khi mo chat moi; refresh/polling chi tai lai trang 1 (100 tin moi
    // nhat) nen khong cham vao token — tranh tai trung trang cu da tai.
    private var chatNextPageToken: String? = null
    private val _chatHasMoreOlder = MutableStateFlow(true)
    val chatHasMoreOlder: StateFlow<Boolean> = _chatHasMoreOlder.asStateFlow()
    private val _isLoadingOlderChat = MutableStateFlow(false)
    val isLoadingOlderChat: StateFlow<Boolean> = _isLoadingOlderChat.asStateFlow()
    private var chatOlderJob: Job? = null

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
            chatOlderJob?.cancel()
            // Chat moi: dat lai phan trang tin cu.
            chatNextPageToken = null
            _chatHasMoreOlder.value = true
            _isLoadingOlderChat.value = false
            if (convId.isNullOrBlank()) {
                _chatMessages.value = emptyList()
                _isChatLoading.value = false
                return@launch
            }
            chatMessagesJob = viewModelScope.launch {
                cache.observeChatMessages(convId).collect { _chatMessages.value = it }
            }
            fetchChatMessagesAndMarkRead(convId, resetPagination = true)
            _isChatLoading.value = false
        }
    }

    /** Tai lai tin nhan that cua cuoc tro chuyen dang mo + danh dau da doc. */
    fun refreshChat(conversationId: String? = _activeConversationId.value) {
        val convId = conversationId ?: return
        viewModelScope.launch { fetchChatMessagesAndMarkRead(convId) }
    }

    private suspend fun fetchChatMessagesAndMarkRead(convId: String, resetPagination: Boolean = false) {
        apiClient.fetchChatMessages(convId).onSuccess { page ->
            cache.saveChatMessages(page.messages)
            if (resetPagination) {
                chatNextPageToken = page.nextPageToken
                _chatHasMoreOlder.value = page.nextPageToken != null
            }
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

    /**
     * Tai them 1 trang tin cu hon (100 tin) cho cuoc tro chuyen dang mo:
     * goi fetchChatMessages voi nextPageToken da luu, gop vao dau danh
     * sach (dedupe theo id that, giu sort cu -> moi). Luu Room thi danh
     * sach hien thi tu cap nhat qua Flow. onLoaded tra so tin moi that su
     * duoc them — man hinh dung de giu vi tri cuon (tin cu chen len dau
     * se day tin dang xem xuong). Het tin cu khi server het tra token.
     */
    fun loadOlderChatMessages(conversationId: String, onLoaded: suspend (Int) -> Unit = {}) {
        val token = chatNextPageToken
        if (!_isLoggedIn.value || _isLoadingOlderChat.value || !_chatHasMoreOlder.value) return
        if (token.isNullOrBlank() || conversationId != _activeConversationId.value) return
        _isLoadingOlderChat.value = true
        chatOlderJob = viewModelScope.launch {
            try {
                val beforeIds = _chatMessages.value.map { it.id }.toSet()
                val page = apiClient.fetchChatMessages(conversationId, token).getOrThrow()
                if (conversationId != _activeConversationId.value) return@launch
                val fresh = page.messages.filter { it.id !in beforeIds }
                if (fresh.isNotEmpty()) {
                    cache.saveChatMessages(fresh)
                    // Doi Room flow day danh sach moi len _chatMessages roi
                    // moi bao xong, de man hinh scrollToItem(anchor + them)
                    // khong vuot qua so item hien co.
                    withTimeoutOrNull(2000) {
                        cache.observeChatMessages(conversationId)
                            .first { list -> fresh.all { f -> list.any { it.id == f.id } } }
                    }
                }
                chatNextPageToken = page.nextPageToken
                _chatHasMoreOlder.value = page.nextPageToken != null
                onLoaded(fresh.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Loi mang: giu nguyen token + hasMore de user cuon lai thu.
            } finally {
                _isLoadingOlderChat.value = false
            }
        }
    }

    /**
     * Reaction len 1 tin nhan chat that (message id that, khong id gia).
     * Toggle nhu goc (decompiled.js ~1733500): tim reaction cua chinh
     * minh tren tin (reactions[].sender == myUid) — bam dung emoji da
     * react thi go (removeChatMessageReaction), bam emoji khac thi gui
     * moi (sendChatMessageReaction). onRemoved chay khi da go xong.
     */
    fun reactToChatMessage(
        conversationId: String,
        messageId: String,
        emoji: String,
        onSuccess: () -> Unit = {},
        onRemoved: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val myUid = sessionManager.currentUser.uid
            val myReaction = _chatMessages.value.firstOrNull { it.id == messageId }
                ?.reactions?.firstOrNull { it.senderUid == myUid }
            val alreadyReactedSame = myReaction?.emoji == emoji
            val res = if (alreadyReactedSame) {
                apiClient.removeChatMessageReaction(conversationId, messageId, emoji)
            } else {
                apiClient.sendChatMessageReaction(conversationId, messageId, emoji)
            }
            if (res.isSuccess) {
                fetchChatMessagesAndMarkRead(conversationId)
                if (alreadyReactedSame) onRemoved() else onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi gửi reaction")
            }
        }
    }

    /** Go thang reaction cua minh khoi 1 tin nhan chat (nut "Go reaction"). */
    fun removeChatMessageReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.removeChatMessageReaction(conversationId, messageId, emoji)
            if (res.isSuccess) {
                fetchChatMessagesAndMarkRead(conversationId)
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi gỡ reaction")
            }
        }
    }

    /**
     * Thu hoi (unsend) 1 tin nhan do minh gui: chi bao thanh cong khi
     * server tra 200; xong xoa tin khoi Room cache (tin se bien khoi man
     * hinh qua Flow) va tai lai danh sach tro chuyen de preview cap nhat.
     */
    fun deleteChatMessage(
        messageId: String,
        conversationId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.deleteChatMessage(messageId, conversationId)
            if (res.isSuccess) {
                cache.deleteChatMessage(messageId)
                refreshConversations()
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi xóa tin nhắn")
            }
        }
    }

    /** Bao cao 1 tin nhan cua nguoi khac len server. */
    fun reportChatMessage(
        messageId: String,
        conversationId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.reportMessage(messageId, conversationId)
            if (res.isSuccess) {
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi báo cáo tin nhắn")
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

    /** Tai lai inbox loi moi ket ban (goi khi mo man Ban be). Loi thi giu danh sach cu. */
    fun refreshIncomingFriendRequests() {
        viewModelScope.launch {
            apiClient.fetchIncomingFriendRequests().onSuccess { requests ->
                if (_isLoggedIn.value) _incomingFriendRequests.value = requests
            }
        }
    }

    /**
     * Chap nhan loi moi ket ban: goi acceptFriendRequest {user_uid}, xong
     * bo nguoi gui khoi inbox va tai lai danh sach ban be that (nguoi vua
     * chap nhan gio la ban).
     */
    fun acceptFriendRequest(
        userUid: String,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.acceptFriendRequest(userUid)
            if (res.isSuccess) {
                _incomingFriendRequests.value = _incomingFriendRequests.value.filter { it.uid != userUid }
                apiClient.fetchFriends(sessionManager.currentUser.uid).onSuccess { liveFriends ->
                    if (liveFriends.isNotEmpty()) {
                        _friends.value = liveFriends
                        cache.saveFriends(liveFriends)
                    }
                }
                onDone()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi chấp nhận kết bạn")
            }
        }
    }

    /**
     * Tu choi loi moi ket ban: goi deleteFriendRequest
     * {user_uid, direction: "incoming"}, xong bo nguoi gui khoi inbox.
     */
    fun declineFriendRequest(
        userUid: String,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.declineFriendRequest(userUid)
            if (res.isSuccess) {
                _incomingFriendRequests.value = _incomingFriendRequests.value.filter { it.uid != userUid }
                onDone()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi từ chối kết bạn")
            }
        }
    }

    /**
     * Xoa 1 moment cua minh: goi deleteMomentV2 {owner_uid, moment_uid,
     * delete_globally}. Chi bao thanh cong khi server tra 200; xong bo
     * moment khoi danh sach hien thi + Room cache (ghi tombstone de sync
     * sau khong tai lai) roi refresh feed that tu server.
     * deleteGlobally = true: xoa cho moi nguoi (ban be khong xem duoc
     * nua); false: chi xoa khoi lich su cua minh (xem ApiClient).
     */
    fun deleteMoment(
        moment: Moment,
        deleteGlobally: Boolean,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.deleteMoment(moment.userUid, moment.id, deleteGlobally)
            if (res.isSuccess) {
                deletedMomentIds.add(moment.id)
                removeMomentsFromCache { it.id == moment.id }
                syncAllData()
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi xóa khoảnh khắc")
            }
        }
    }

    /** Bao cao 1 moment cua nguoi khac len server (reportMoment). */
    fun reportMoment(
        moment: Moment,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.reportMoment(moment.userUid, moment.id)
            if (res.isSuccess) {
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi báo cáo khoảnh khắc")
            }
        }
    }

    /**
     * Chan 1 nguoi: goi blockFriend {user_uid}, xong bo toan bo moment cua
     * ho khoi feed hien thi + Room cache va bo ho khoi danh sach ban be
     * (cache + hien thi). Khong dong vao cuoc tro chuyen cu: goc chi chan
     * tin moi (nguoi bi chan khong gui tin / xem Locket duoc nua).
     */
    fun blockFriend(
        userUid: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.blockFriend(userUid)
            if (res.isSuccess) {
                removeMomentsFromCache { it.userUid == userUid }
                val updatedFriends = _friends.value.filter { it.uid != userUid }
                _friends.value = updatedFriends
                cache.saveFriends(updatedFriends)
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi chặn người dùng")
            }
        }
    }

    /**
     * Bo chan 1 nguoi: goi unblockUser {user_uid}, xong bo ho khoi danh
     * sach chan hien thi. Danh sach ban be/moment se ve lai o lan dong bo
     * sau (syncAllData) neu server tra lai.
     */
    fun unblockUser(
        userUid: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.unblockUser(userUid)
            if (res.isSuccess) {
                _blockedUsers.value = _blockedUsers.value.filter { it.uid != userUid }
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi bỏ chặn người dùng")
            }
        }
    }

    // ---- Ho so: doi ten / username / anh dai dien / link moi (batch 5) ----

    /** Doc lai profile tu server (fetchUserV2) roi cap nhat UI + session. */
    fun refreshProfile(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val uid = sessionManager.currentUser.uid
            if (uid.isBlank()) {
                onDone()
                return@launch
            }
            apiClient.fetchUserProfile(uid).onSuccess { user ->
                _currentUser.value = user
                sessionManager.currentUser = user
            }
            onDone()
        }
    }

    /** Doi ten hien thi qua changeProfileInfo {first_name, last_name}. */
    fun updateProfileName(
        firstName: String,
        lastName: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.changeProfileInfo(firstName, lastName)
            if (res.isSuccess) refreshProfile(onSuccess)
            else onError(res.exceptionOrNull()?.message ?: "Lỗi đổi tên hồ sơ")
        }
    }

    /**
     * Doi anh dai dien: upload JPEG len Storage
     * users/{uid}/public/profile_pic.jpg (locket-img) roi
     * changeProfileInfo {profile_picture_url} nhu goc.
     */
    fun updateProfilePicture(
        imageBytes: ByteArray,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val up = apiClient.uploadProfilePicture(imageBytes)
            if (!up.success || up.downloadUrl.isNullOrBlank()) {
                onError(up.errorMessage ?: "Lỗi tải ảnh lên")
                return@launch
            }
            val res = apiClient.changeProfilePictureUrl(up.downloadUrl)
            if (res.isSuccess) refreshProfile(onSuccess)
            else onError(res.exceptionOrNull()?.message ?: "Lỗi đổi ảnh đại diện")
        }
    }

    /**
     * Link moi that (generateInviteToken + createDynamicLink). Chi tao 1
     * lan roi giu trong RAM — goc cache theo uid trong MMKV
     * (getInviteLink/setInviteLink); repo chua luu persistent.
     */
    private val _inviteLink = MutableStateFlow<String?>(null)
    val inviteLink: StateFlow<String?> = _inviteLink.asStateFlow()

    private val _inviteLinkLoading = MutableStateFlow(false)
    val inviteLinkLoading: StateFlow<Boolean> = _inviteLinkLoading.asStateFlow()

    fun loadInviteLink(onError: (String) -> Unit = {}) {
        if (_inviteLink.value != null || _inviteLinkLoading.value) return
        viewModelScope.launch {
            _inviteLinkLoading.value = true
            apiClient.createInviteLink()
                .onSuccess { _inviteLink.value = it }
                .onFailure { onError(it.message ?: "Không tạo được link mời") }
            _inviteLinkLoading.value = false
        }
    }

    /**
     * An moment cua 1 ban khoi feed: goi toggleFriendHidden {user_uid}
     * (server tu doi trang thai), xong bo moment cua ho khoi feed hien
     * thi + Room cache. KHONG xoa ho khoi danh sach ban be — goc noi ro
     * 2 ben van o trong danh sach ban be cua nhau khi an.
     */
    fun hideFriend(
        userUid: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = apiClient.toggleFriendHidden(userUid)
            if (res.isSuccess) {
                hiddenFriendUids.add(userUid)
                removeMomentsFromCache { it.userUid == userUid }
                onSuccess()
            } else {
                onError(res.exceptionOrNull()?.message ?: "Lỗi ẩn bạn bè")
            }
        }
    }

    /** Tai lai danh sach that cac tai khoan bi chan (goi khi mo man Profile). Loi thi giu danh sach cu. */
    fun refreshBlockedUsers() {
        viewModelScope.launch {
            apiClient.fetchBlockedUsers().onSuccess { blocked ->
                if (_isLoggedIn.value) _blockedUsers.value = blocked
            }
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
        ownHistoryJob?.cancel()
        ownHistoryCursor = null
        _ownHistoryLoading.value = false
        _ownHistoryHasMore.value = true
        _ownHistoryError.value = null
        _ownHistoryCoveredThrough.value = null
        historyJob?.cancel()
        historyCursor = null
        _historyHasMore.value = true
        _historyError.value = null
        _historyLoading.value = false
        sessionManager.logout()
        viewModelScope.launch { cache.clearAll() }
        chatMessagesJob?.cancel()
        chatOlderJob?.cancel()
        chatNextPageToken = null
        _chatHasMoreOlder.value = true
        _isLoadingOlderChat.value = false
        _chatMessages.value = emptyList()
        _activeConversationId.value = null
        _isChatLoading.value = false
        _currentUser.value = sessionManager.currentUser
        _isLoggedIn.value = false
        _friends.value = emptyList()
        _incomingFriendRequests.value = emptyList()
        _blockedUsers.value = emptyList()
        deletedMomentIds.clear()
        hiddenFriendUids.clear()
        _moments.value = emptyList()
        _conversations.value = emptyList()
    }
}
