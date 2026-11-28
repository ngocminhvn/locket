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
import com.locket.app.model.RecommendedFriend
import com.locket.app.model.User
import com.locket.app.rust.RustLocketCore
import com.locket.app.storage.SessionManager
import com.locket.app.storage.db.LocketCache
import com.locket.app.widget.LocketWidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Goi y ket ban (Batch 6, defensive): chi co gia tri khi server tra 200;
    // loi/401 thi rong -> UI an section, khong bao loi.
    private val _friendRecommendations = MutableStateFlow<List<RecommendedFriend>>(emptyList())
    val friendRecommendations: StateFlow<List<RecommendedFriend>> = _friendRecommendations.asStateFlow()

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
    private val conversationSyncLock = Mutex()
    private val chatSyncLock = Mutex()
    private var syncJob: Job? = null
    private var feedListenJob: Job? = null
    private var chatListenJob: Job? = null
    private var foreground = false
    private var cacheHydrated = false
    private var friendsListenJob: Job? = null
    private var conversationsListenJob: Job? = null
    private val friendsSyncLock = Mutex()
    private var friendsLive = false
    private var conversationsLive = false
    private val _feedLoading = MutableStateFlow(false)
    val feedLoading = _feedLoading.asStateFlow()
    private val _feedError = MutableStateFlow<String?>(null)
    val feedError = _feedError.asStateFlow()
    private val _feedRealtime = MutableStateFlow(false)
    val feedRealtime = _feedRealtime.asStateFlow()
    private val _chatRealtime = MutableStateFlow(false)
    val chatRealtime = _chatRealtime.asStateFlow()
    private var syncAgain = false
    private var chatOpenJob: Job? = null
    private var chatPollJob: Job? = null
    private var activeFriendUid: String? = null
    private var lastConversationSync = 0L
    private val chatRefreshedAt = mutableMapOf<String, Long>()
    private val chatPagination = mutableMapOf<String, String?>()
    private val chatMarkedThrough = mutableMapOf<String, String>()
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private fun activeAccount(uid: String) = _isLoggedIn.value && uid == sessionManager.currentUser.uid

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
    private val videoPrefetchJobs = mutableMapOf<String, Job>()
    private val videoPrefetched = linkedSetOf<String>()

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

    // Luot xem moment CUA MINH (Batch 7a): map momentId -> so luot xem.
    // Chi co gia tri khi server tra 200; loi/401 -> khong co entry ->
    // UI an chip, khong hien so gia.
    private val _momentViewCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val momentViewCounts: StateFlow<Map<String, Int>> = _momentViewCounts.asStateFlow()
    private val _momentViewers = MutableStateFlow<Map<String, List<Friend>>>(emptyMap())
    val momentViewers = _momentViewers.asStateFlow()
    private val _momentViewErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val momentViewErrors = _momentViewErrors.asStateFlow()
    private val _momentViewsLoading = MutableStateFlow<Set<String>>(emptySet())
    val momentViewsLoading = _momentViewsLoading.asStateFlow()

    // Nut tat "Hien luot xem" o Mod Center. Mac dinh bat; tat thi an
    // chip luot xem du server co tra so lieu.
    private val _showViewCount = MutableStateFlow(sessionManager.showViewCount)
    val showViewCount: StateFlow<Boolean> = _showViewCount.asStateFlow()

    fun setShowViewCount(show: Boolean) {
        sessionManager.showViewCount = show
        _showViewCount.value = show
    }

    /**
     * Tai luot xem cho 1 moment CUA MINH (owner_uid == myUid). Khong goi
     * cho moment cua ban (server tra 401 "User does not have access to
     * this moment"). Loi thi giu gia tri cu (hoac rong -> an chip).
     */
    fun refreshMomentViews(moment: Moment) {
        val owner = sessionManager.currentUser.uid
        if (owner.isBlank() || moment.userUid != owner || !activeAccount(owner) || moment.id in _momentViewsLoading.value) return
        _momentViewsLoading.update { it + moment.id }
        _momentViewErrors.update { it - moment.id }
        viewModelScope.launch {
            try {
                apiClient.getMomentViews(moment.id).onSuccess { viewers ->
                    if (!activeAccount(owner)) return@onSuccess
                    val friendsByUid = _friends.value.associateBy { it.uid }
                    val resolved = viewers.map { viewer ->
                        val cached = friendsByUid[viewer.uid]
                        if (cached != null) cached else viewer
                    }
                    _momentViewers.update { it + (moment.id to resolved) }
                    _momentViewCounts.update { it + (moment.id to resolved.size) }
                    // Resolve unknown users from the same authenticated profile API.
                    for (viewer in resolved.filter { it.displayName.isBlank() && it.username.isBlank() }) {
                        if (!activeAccount(owner)) break
                        val profile = apiClient.fetchUserProfile(viewer.uid).getOrNull() ?: continue
                        if (!activeAccount(owner)) break
                        _momentViewers.update { map ->
                            val current = map[moment.id] ?: return@update map
                            map + (moment.id to current.map {
                                if (it.uid == viewer.uid) it.copy(displayName = profile.displayName,
                                    username = profile.username, avatarUrl = profile.profilePictureUrl) else it
                            })
                        }
                    }
                }.onFailure { error ->
                    if (activeAccount(owner)) _momentViewErrors.update { it + (moment.id to (error.message ?: "Không tải được hoạt động")) }
                }
            } finally {
                if (activeAccount(owner)) _momentViewsLoading.update { it - moment.id }
            }
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
        val fresh = urls.distinct().filter { it.isNotBlank() && prefetchedThumbnails.add(it) }
        if (prefetchedThumbnails.size > 300) {
            prefetchedThumbnails.clear()
            prefetchedThumbnails.addAll(fresh)
        }
        if (fresh.isEmpty()) return
        val app = LocketApplication.instance
        val loader = app.imageLoader
        for (url in fresh) {
            loader.enqueue(ImageRequest.Builder(app).data(url)
                .size(app.resources.displayMetrics.widthPixels)
                .listener(onError = { _, _ ->
                    viewModelScope.launch { prefetchedThumbnails.remove(url) }
                }).build())
        }
    }

    /**
     * Prefetch thumbnail cua `count` moment ke tiep sau `currentIndex`
     * trong danh sach da sap xep moi-nhat-truoc. currentIndex = -1 (dang o
     * camera) thi prefetch tu moment moi nhat.
     */
    fun prefetchUpcomingMoments(moments: List<Moment>, currentIndex: Int, count: Int = 8, forward: Boolean = true) {
        if (moments.isEmpty()) return
        val next = if (forward) ((currentIndex + 1)..(currentIndex + count)).toList()
            else ((currentIndex - 1) downTo (currentIndex - count)).toList()
        val opposite = if (forward) listOf(currentIndex - 1, currentIndex - 2)
            else listOf(currentIndex + 1, currentIndex + 2)
        prefetchThumbnails((next + opposite).mapNotNull { moments.getOrNull(it)?.thumbnailUrl })
        val nearbyVideos = (-3..3).filter { it != 0 }.mapNotNull { moments.getOrNull(currentIndex + it)?.videoUrl?.takeIf(String::isNotBlank) }.toSet()
        videoPrefetchJobs.keys.filter { it !in nearbyVideos }.forEach { videoPrefetchJobs.remove(it)?.cancel() }
        for (url in nearbyVideos) {
            if (url in videoPrefetched || videoPrefetchJobs[url]?.isActive == true) continue
            videoPrefetchJobs[url] = viewModelScope.launch {
                try {
                    MomentVideoCache.prefetch(LocketApplication.instance, url)
                    videoPrefetched.add(url)
                    while (videoPrefetched.size > 48) videoPrefetched.remove(videoPrefetched.first())
                } finally { videoPrefetchJobs.remove(url) }
            }
        }
    }

    private fun knownMomentNames(incoming: List<Moment>): List<Moment> {
        val friends = _friends.value.associateBy { it.uid }
        val own = _currentUser.value
        val old = _moments.value.associateBy { it.id }
        return incoming.map { m ->
            val friend = friends[m.userUid]
            val prior = old[m.id]
            val name = when {
                m.userUid == own.uid -> own.displayName
                friend != null && friend.displayName != friend.uid -> friend.displayName
                else -> m.userName.takeUnless { it == m.userUid }.orEmpty()
            }.ifBlank { prior?.userName.orEmpty() }.ifBlank { m.userUid }
            val avatar = when {
                m.userUid == own.uid -> own.profilePictureUrl
                friend != null -> friend.avatarUrl
                else -> m.userAvatarUrl
            }.ifBlank { prior?.userAvatarUrl.orEmpty() }
            m.copy(userName = name, userAvatarUrl = avatar)
        }
    }

    private fun enrichMomentNames(account: String, records: List<Moment>) {
        val missing = records.filter { it.userName.isBlank() || it.userName == it.userUid }.map { it.userUid }.distinct()
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val names = apiClient.resolveUserNames(missing)
            if (!activeAccount(account) || names.isEmpty()) return@launch
            momentWriteLock.withLock {
                if (!activeAccount(account)) return@withLock
                val updated = _moments.value.map { m ->
                    val name = names[m.userUid]
                    if (name != null && (m.userName.isBlank() || m.userName == m.userUid))
                        m.copy(userName = name.first, userAvatarUrl = name.second.ifBlank { m.userAvatarUrl }) else m
                }
                if (updated != _moments.value) {
                    cache.saveMoments(updated)
                    if (activeAccount(account)) _moments.value = updated
                }
            }
        }
    }

    private suspend fun mergeMoments(incoming: List<Moment>) = momentWriteLock.withLock {
        val merged = if (RustLocketCore.isAvailable) {
            RustLocketCore.mergeMoments(_moments.value, incoming, deletedMomentIds, hiddenFriendUids)
        } else {
            (_moments.value + incoming)
                .filter { it.id !in deletedMomentIds && it.userUid !in hiddenFriendUids }
                .associateBy { it.id }.values
                .sortedByDescending { it.timestampSeconds }
        }
        if (merged != _moments.value) {
            cache.saveMoments(merged)
            _moments.value = merged
        }
    }

    /**
     * Bo moment khoi danh sach hien thi + Room cache (dung sau khi xoa /
     * chan / an thanh cong tren server). Chay duoi momentWriteLock nhu
     * mergeMoments de khong mat update khi sync chay song song.
     */
    private suspend fun removeMomentsFromCache(predicate: (Moment) -> Boolean) = momentWriteLock.withLock {
        val updated = _moments.value.filterNot(predicate)
        if (updated != _moments.value) {
            cache.saveMoments(updated)
            _moments.value = updated
        }
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
                val resolved = knownMomentNames(incoming)
                mergeMoments(resolved)
                enrichMomentNames(account, resolved)
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
    data class PendingChat(val token: String, val receiver: String, val text: String, val createdAt: Long, val acknowledged: Boolean = false)
    private val _pendingChat = MutableStateFlow<List<PendingChat>>(emptyList())
    val pendingChat = _pendingChat.asStateFlow()
    private fun reconcilePending(messages: List<ChatMessage>) {
        val tokens = messages.mapNotNull { it.clientToken }.toSet()
        if (tokens.isNotEmpty()) _pendingChat.update { rows -> rows.filterNot { it.token in tokens } }
    }

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
        val startupAccount = sessionManager.currentUser.uid
        viewModelScope.launch {
            cache.migrateLegacyCacheIfNeeded()
            if (activeAccount(startupAccount) && startupAccount.isNotBlank()) {
                val savedMoments = cache.observeMoments().first()
                val savedFriends = cache.observeFriends().first()
                val savedConversations = cache.observeConversations().first()
                if (!activeAccount(startupAccount)) return@launch
                _moments.value = savedMoments
                _friends.value = savedFriends
                _conversations.value = savedConversations
                apiClient.seedUserNames(_friends.value)
                apiClient.seedUserNames(_moments.value.map { Friend(it.userUid, "", it.userName, it.userAvatarUrl) })
                cacheHydrated = true
                prefetchUpcomingMoments(_moments.value, -1)
                syncAllData()
            } else { cacheHydrated = true; if (_isLoggedIn.value) syncAllData() }
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
        if (RustLocketCore.isAvailable) return RustLocketCore.calculateStreak(momentsList)
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

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (active) {
            startFeedListener()
            startSocialListeners()
            _activeConversationId.value?.let { startChatListener(it) }
        } else {
            feedListenJob?.cancel(); feedListenJob = null
            chatListenJob?.cancel(); chatListenJob = null
            friendsListenJob?.cancel(); friendsListenJob = null
            conversationsListenJob?.cancel(); conversationsListenJob = null
            friendsLive = false; conversationsLive = false
            _feedRealtime.value = false
            _chatRealtime.value = false
            videoPrefetchJobs.values.toList().forEach { it.cancel() }
            videoPrefetchJobs.clear()
        }
    }

    private suspend fun applyFeed(account: String, records: List<Moment>) {
        if (!activeAccount(account) || records.isEmpty()) return
        val resolved = knownMomentNames(records)
        mergeMoments(resolved)
        prefetchThumbnails(resolved.take(8).map { it.thumbnailUrl })
        enrichMomentNames(account, resolved)
        if (activeAccount(account)) LocketWidgetUpdater.updateAll(LocketApplication.instance)
    }

    private suspend fun refreshFeedNow(account: String) = feedSyncLock.withLock {
        if (!activeAccount(account)) return@withLock
        _feedLoading.value = true
        _feedError.value = null
        try {
            // Read the same history collection as Listen, not the widget endpoint.
            val page = apiClient.fetchHistoryPage().getOrThrow()
            if (activeAccount(account)) applyFeed(account, page.moments)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (activeAccount(account)) _feedError.value = e.message ?: "Không tải được khoảnh khắc" }
        finally { if (activeAccount(account)) _feedLoading.value = false }
    }

    fun refreshFeed() {
        val account = sessionManager.currentUser.uid
        if (account.isNotBlank() && activeAccount(account)) viewModelScope.launch { refreshFeedNow(account) }
    }

    private fun startFeedListener() {
        val account = sessionManager.currentUser.uid
        if (!foreground || !cacheHydrated || !activeAccount(account) || account.isBlank() || feedListenJob?.isActive == true) return
        feedListenJob = viewModelScope.launch {
            var retry = 1_000L
            while (foreground && activeAccount(account)) {
                try {
                    apiClient.listenFeed(account).collect { delta ->
                        if (!activeAccount(account)) return@collect
                        if (delta.deletedIds.isNotEmpty()) {
                            deletedMomentIds.addAll(delta.deletedIds)
                            removeMomentsFromCache { it.id in delta.deletedIds }
                        }
                        applyFeed(account, delta.upserts)
                        if (delta.ready) {
                            retry = 1_000L
                            _feedRealtime.value = true
                            _feedError.value = null
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.w("LocketListen", "Feed Listen disconnected: " + e.javaClass.simpleName)
                } finally { _feedRealtime.value = false }
                if (foreground && activeAccount(account)) {
                    refreshFeedNow(account)
                    kotlinx.coroutines.delay(retry)
                    retry = (retry * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun startChatListener(convId: String) {
        val account = sessionManager.currentUser.uid
        if (!foreground || !activeAccount(account)) return
        chatListenJob?.cancel()
        chatListenJob = viewModelScope.launch {
            var retry = 1_000L
            while (foreground && activeAccount(account) && _activeConversationId.value == convId) {
                try {
                    apiClient.listenChat(account, convId).collect { delta ->
                        if (!activeAccount(account) || _activeConversationId.value != convId) return@collect
                        chatSyncLock.withLock {
                            delta.deletedIds.forEach { cache.deleteChatMessage(it) }
                            if (delta.upserts.isNotEmpty()) cache.saveChatMessages(delta.upserts)
                        }
                        if (delta.ready) { retry = 1_000L; _chatRealtime.value = true }
                        val newest = delta.upserts.maxByOrNull { it.createdAtMs }?.id
                        if (newest != null && chatMarkedThrough[convId] != newest) {
                            apiClient.markConversationAsRead(convId).onSuccess { chatMarkedThrough[convId] = newest }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("LocketListen", "Chat Listen disconnected: " + e.javaClass.simpleName) }
                finally { _chatRealtime.value = false }
                if (foreground && activeAccount(account) && _activeConversationId.value == convId) {
                    fetchChatMessagesAndMarkRead(convId, incremental = true)
                    kotlinx.coroutines.delay(retry)
                    retry = (retry * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun publishFriends(account: String, friends: List<Friend>) {
        if (!activeAccount(account)) return
        apiClient.seedUserNames(friends)
        if (friends != _friends.value) {
            cache.saveFriends(friends)
            if (!activeAccount(account)) return
            _friends.value = friends
        }
        momentWriteLock.withLock {
            if (!activeAccount(account)) return@withLock
            val updated = knownMomentNames(_moments.value)
            if (updated != _moments.value) {
                cache.saveMoments(updated)
                if (activeAccount(account)) _moments.value = updated
            }
        }
    }

    private suspend fun refreshFriendsNow(account: String) = friendsSyncLock.withLock {
        if (!activeAccount(account) || friendsLive) return@withLock
        apiClient.fetchFriends(account, _friends.value).onSuccess { publishFriends(account, it) }
    }

    private fun startSocialListeners() {
        val account = sessionManager.currentUser.uid
        if (!foreground || !cacheHydrated || account.isBlank() || !activeAccount(account)) return
        if (friendsListenJob?.isActive != true) friendsListenJob = viewModelScope.launch {
            var retry = 1_000L
            while (foreground && activeAccount(account)) {
                try {
                    apiClient.listenFriends(account).collect { delta ->
                        if (!activeAccount(account)) return@collect
                        friendsSyncLock.withLock {
                            if (!activeAccount(account)) return@withLock
                            val existing = _friends.value.associateBy { it.uid }
                            val updated = if (delta.ready) linkedMapOf<String, Friend>() else LinkedHashMap(existing)
                            delta.deletedIds.forEach { updated.remove(it) }
                            val missing = delta.upserts.filter { it !in existing }
                            val names = if (missing.isEmpty()) emptyMap() else apiClient.resolveUserNames(missing)
                            delta.upserts.forEach { uid ->
                                updated[uid] = existing[uid] ?: names[uid]?.let { Friend(uid, uid, it.first, it.second) }
                                    ?: Friend(uid, uid, uid)
                            }
                            publishFriends(account, updated.values.toList())
                            if (delta.ready) { retry = 1_000L; friendsLive = true }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("LocketListen", "Friends disconnected: " + e.javaClass.simpleName) }
                finally { friendsLive = false }
                if (foreground && activeAccount(account)) {
                    refreshFriendsNow(account)
                    kotlinx.coroutines.delay(retry); retry = (retry * 2).coerceAtMost(30_000L)
                }
            }
        }
        if (conversationsListenJob?.isActive != true) conversationsListenJob = viewModelScope.launch {
            var retry = 1_000L
            while (foreground && activeAccount(account)) {
                try {
                    apiClient.listenConversations(account).collect { delta ->
                        if (!activeAccount(account)) return@collect
                        conversationSyncLock.withLock {
                            if (!activeAccount(account)) return@withLock
                            val old = _conversations.value
                            val known = (old.map { it.friend } + _friends.value).associateBy { it.uid }
                            val updated = if (delta.ready) linkedMapOf<String, Conversation>() else LinkedHashMap(old.associateBy { it.id })
                            delta.deletedIds.forEach { updated.remove(it) }
                            delta.upserts.forEach { conv ->
                                updated[conv.id] = conv.copy(friend = mergeConversationFriend(conv.friend, known[conv.friend.uid]))
                            }
                            val resolved = updated.values.sortedWith(compareByDescending<Conversation> { it.lastMessageAtMs }.thenBy { it.id })
                            if (resolved != old) {
                                cache.saveConversations(resolved)
                                if (activeAccount(account)) _conversations.value = resolved
                            }
                            if (delta.ready) { retry = 1_000L; conversationsLive = true }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("LocketListen", "Conversations disconnected: " + e.javaClass.simpleName) }
                finally { conversationsLive = false }
                if (foreground && activeAccount(account)) {
                    refreshConversations(force = true)
                    kotlinx.coroutines.delay(retry); retry = (retry * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    fun syncAllData() {
        if (syncJob?.isActive == true) {
            syncAgain = true
            return
        }
        val account = sessionManager.currentUser.uid
        if (account.isBlank() || !_isLoggedIn.value) return
        startFeedListener()
        startSocialListeners()
        _feedLoading.value = true
        syncJob = viewModelScope.launch {
            _isSyncing.value = true
            try {
                if (!apiClient.ensureFreshToken() || !activeAccount(account)) return@launch
                apiClient.seedUserNames(_friends.value)
                supervisorScope {
                    // Start the feed immediately. History starts only once feed
                    // has been delivered, not after unrelated profile/chat requests.
                    launch {
                        refreshFeedNow(account)
                        if (activeAccount(account) && historyCursor == null && _historyHasMore.value && _historyError.value == null) loadOlderMoments()
                    }
                    launch {
                        apiClient.fetchUserProfile(account).onSuccess { user ->
                            if (activeAccount(account)) {
                                _currentUser.value = user
                                sessionManager.currentUser = user
                            }
                        }
                    }
                    launch { refreshFriendsNow(account) }
                    launch { refreshConversations(force = false) }
                }
            } finally {
                _isSyncing.value = false
                if (activeAccount(account)) _feedLoading.value = false
                syncJob = null
                if (syncAgain && activeAccount(account)) {
                    syncAgain = false
                    syncAllData()
                }
            }
        }
    }

    /** Cached list remains visible; coalesce repeated screen-entry refreshes. */
    suspend fun refreshConversations(force: Boolean = true) {
        val account = sessionManager.currentUser.uid
        if (account.isBlank() || !_isLoggedIn.value) return
        conversationSyncLock.withLock {
            if (!activeAccount(account)) return@withLock
            if (!force && conversationsLive) return@withLock
            if (!force && lastConversationSync != 0L && now() - lastConversationSync < 30_000L) return@withLock
            apiClient.seedUserNames(_friends.value)
            val live = apiClient.fetchConversations(account, resolveNames = false).getOrNull() ?: return@withLock
            if (!activeAccount(account)) return@withLock
            val cached = (_conversations.value.map { it.friend } + _friends.value).associateBy { it.uid }
            val resolved = live.map { c -> c.copy(friend = mergeConversationFriend(c.friend, cached[c.friend.uid])) }
            if (resolved != _conversations.value) {
                cache.saveConversations(resolved)
                if (activeAccount(account)) _conversations.value = resolved
            }
            lastConversationSync = now()
            val missing = resolved.filter { it.friend.displayName.isBlank() || it.friend.displayName == it.friend.uid }.map { it.friend.uid }
            if (missing.isNotEmpty()) viewModelScope.launch {
                val names = apiClient.resolveUserNames(missing)
                conversationSyncLock.withLock {
                    if (!activeAccount(account)) return@withLock
                    val updated = _conversations.value.map { c ->
                        val name = names[c.friend.uid]
                        if (name != null && (c.friend.displayName.isBlank() || c.friend.displayName == c.friend.uid))
                            c.copy(friend = c.friend.copy(displayName = name.first, avatarUrl = name.second.ifBlank { c.friend.avatarUrl }))
                        else c
                    }
                    if (updated != _conversations.value) {
                        cache.saveConversations(updated)
                        if (activeAccount(account)) _conversations.value = updated
                    }
                }
            }
        }
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
    fun closeChat() {
        chatOpenJob?.cancel()
        chatListenJob?.cancel(); chatListenJob = null
        _chatRealtime.value = false
        chatPollJob?.cancel()
        chatMessagesJob?.cancel()
        chatOlderJob?.cancel()
        activeFriendUid = null
        _activeConversationId.value = null
        _chatMessages.value = emptyList()
        _isChatLoading.value = false
        _isLoadingOlderChat.value = false
    }

    fun openChat(friendUid: String) {
        closeChat()
        activeFriendUid = friendUid
        val account = sessionManager.currentUser.uid
        _isChatLoading.value = true
        chatOpenJob = viewModelScope.launch {
            try {
                var convId = _conversations.value.firstOrNull { it.friend.uid == friendUid }?.id
                if (convId == null) {
                    val local = cache.observeConversations().first()
                    convId = local.firstOrNull { it.friend.uid == friendUid }?.id
                }
                if (convId == null) {
                    refreshConversations(force = true)
                    convId = _conversations.value.firstOrNull { it.friend.uid == friendUid }?.id
                }
                val id = convId ?: return@launch
                if (!activeAccount(account) || activeFriendUid != friendUid) return@launch
                _activeConversationId.value = id
                val initialized = chatPagination.containsKey(id)
                chatNextPageToken = chatPagination[id]
                _chatHasMoreOlder.value = !initialized || chatNextPageToken != null
                _isLoadingOlderChat.value = false
                val saved = cache.observeChatMessages(id).first()
                if (!activeAccount(account) || activeFriendUid != friendUid) return@launch
                _chatMessages.value = saved
                chatMessagesJob = viewModelScope.launch {
                    cache.observeChatMessages(id).collect { if (_activeConversationId.value == id) { _chatMessages.value = it; reconcilePending(it) } }
                }
                // Cache is displayed before any network response.
                if (_chatMessages.value.isNotEmpty()) _isChatLoading.value = false
                val recentlyRefreshed = chatRefreshedAt[id]?.let { now() - it < 15_000L } == true
                val unread = _conversations.value.any { it.id == id && it.unreadCount > 0 }
                if (activeAccount(account) && activeFriendUid == friendUid) startChatListener(id)
                if (!initialized || !recentlyRefreshed || unread || saved.isEmpty()) {
                    fetchChatMessagesAndMarkRead(id, resetPagination = !initialized, incremental = initialized)
                }
            } finally {
                if (activeFriendUid == friendUid) _isChatLoading.value = false
            }
        }
    }

    fun refreshChat(conversationId: String? = _activeConversationId.value) {
        val convId = conversationId ?: return
        if (chatPollJob?.isActive == true || chatOpenJob?.isActive == true || convId != _activeConversationId.value) return
        chatPollJob = viewModelScope.launch { fetchChatMessagesAndMarkRead(convId, incremental = true) }
    }

    private suspend fun fetchChatMessagesAndMarkRead(convId: String, resetPagination: Boolean = false, incremental: Boolean = false) {
        val account = sessionManager.currentUser.uid
        chatSyncLock.withLock {
            if (!activeAccount(account) || _activeConversationId.value != convId) return@withLock
            if (!apiClient.ensureFreshToken()) return@withLock
            val old = _chatMessages.value
            // Small overlapping window for polling; if it does not overlap
            // the cached newest message, fall back to the original page size.
            val recent = incremental && old.isNotEmpty()
            var page = apiClient.fetchChatMessages(convId, pageSize = if (recent) 24 else 100).getOrNull() ?: return@withLock
            val needsFullRefresh = if (RustLocketCore.isAvailable) {
                RustLocketCore.needsFullChatRefresh(recent, old.lastOrNull()?.id, page.messages)
            } else recent && old.last().id !in page.messages.map { it.id }
            if (needsFullRefresh) {
                page = apiClient.fetchChatMessages(convId).getOrNull() ?: return@withLock
            }
            if (!activeAccount(account) || _activeConversationId.value != convId) return@withLock
            cache.saveChatMessages(page.messages)
            if (resetPagination) {
                chatNextPageToken = page.nextPageToken
                chatPagination[convId] = page.nextPageToken
                _chatHasMoreOlder.value = page.nextPageToken != null
            }
            chatRefreshedAt[convId] = now()
            val latest = page.messages.lastOrNull()?.id
            val unread = _conversations.value.any { it.id == convId && it.unreadCount > 0 }
            if ((latest != null && chatMarkedThrough[convId] != latest) || unread) {
                apiClient.markConversationAsRead(convId).onSuccess {
                    if (!activeAccount(account) || _activeConversationId.value != convId) return@onSuccess
                    if (latest != null) chatMarkedThrough[convId] = latest
                    conversationSyncLock.withLock {
                        if (!activeAccount(account)) return@withLock
                        val updated = _conversations.value.map { if (it.id == convId) it.copy(unreadCount = 0) else it }
                        if (updated != _conversations.value) {
                            cache.saveConversations(updated)
                            if (activeAccount(account)) _conversations.value = updated
                        }
                    }
                }
            }
        }
    }

    // Real pending work, independent from camera/review composables. Never
    // inject a synthetic posted moment into the feed.
    data class MomentUploadFailure(val id: Long, val message: String, val mayHavePosted: Boolean)
    private data class QueuedMoment(
        val id: Long,
        val owner: String,
        val bitmap: Bitmap,
        val video: File?,
        val prepared: Deferred<ByteArray>?,
        val caption: String,
        val recipients: List<String>,
        var bytes: ByteArray? = null,
        var thumbnailUrl: String? = null,
        var md5: String? = null,
        var videoUrl: String? = null,
        var attemptedPost: Boolean = false
    )
    private val pendingUploads = ArrayDeque<QueuedMoment>()
    private val failedUploads = linkedMapOf<Long, QueuedMoment>()
    private var activeUpload: QueuedMoment? = null
    private var uploadJob: Job? = null
    private var uploadSequence = 0L
    private val _momentUploadFailures = MutableStateFlow<List<MomentUploadFailure>>(emptyList())
    val momentUploadFailures = _momentUploadFailures.asStateFlow()

    internal fun prepareMomentThumbnail(bitmap: Bitmap): Deferred<ByteArray> =
        viewModelScope.async(Dispatchers.Default) { encodeMomentThumbnail(bitmap) }

    internal fun enqueueMoment(
        bitmap: Bitmap,
        caption: String,
        recipients: List<String>,
        videoFile: File?,
        preparedThumbnail: Deferred<ByteArray>?
    ): Boolean {
        val owner = sessionManager.currentUser.uid
        if (owner.isBlank() || !activeAccount(owner) ||
            pendingUploads.size + failedUploads.size + (if (activeUpload != null) 1 else 0) >= 8) return false
        pendingUploads.addLast(QueuedMoment(++uploadSequence, owner, bitmap, videoFile,
            preparedThumbnail, caption, recipients.toList()))
        drainMomentUploads()
        return true
    }

    fun retryMomentUpload(id: Long) {
        val item = failedUploads[id] ?: return
        if (!activeAccount(item.owner)) return
        failedUploads.remove(id)
        _momentUploadFailures.update { list -> list.filterNot { it.id == id } }
        pendingUploads.addLast(item)
        drainMomentUploads()
    }

    fun discardMomentUpload(id: Long) {
        val item = failedUploads.remove(id) ?: return
        releaseUpload(item)
        _momentUploadFailures.update { list -> list.filterNot { it.id == id } }
    }

    private fun releaseUpload(item: QueuedMoment) {
        item.prepared?.cancel()
        item.video?.delete()
    }

    private fun drainMomentUploads() {
        if (uploadJob?.isActive == true) return
        uploadJob = viewModelScope.launch {
            while (pendingUploads.isNotEmpty()) {
                val item = pendingUploads.removeFirst()
                activeUpload = item
                try {
                    check(activeAccount(item.owner)) { "Phiên đăng nhập đã thay đổi" }
                    uploadQueuedMoment(item)
                    releaseUpload(item)
                    if (activeAccount(item.owner)) refreshFeed()
                } catch (e: CancellationException) {
                    releaseUpload(item)
                    throw e
                } catch (e: Exception) {
                    if (activeAccount(item.owner)) {
                        failedUploads[item.id] = item
                        _momentUploadFailures.update { list ->
                            list + MomentUploadFailure(item.id, e.message ?: "Không gửi được khoảnh khắc", item.attemptedPost)
                        }
                    } else releaseUpload(item)
                } finally {
                    activeUpload = null
                }
            }
        }
    }

    private suspend fun uploadQueuedMoment(item: QueuedMoment) {
        val webp = item.video == null
        val bytes = item.bytes ?: (if (webp) {
            try {
                item.prepared?.await() ?: withContext(Dispatchers.Default) { encodeMomentThumbnail(item.bitmap) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Default) { encodeMomentThumbnail(item.bitmap) }
            }
        } else withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { stream ->
                check(item.bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream))
                stream.toByteArray()
            }
        }).also { item.bytes = it }

        check(activeAccount(item.owner)) { "Phiên đăng nhập đã thay đổi" }
        if (item.thumbnailUrl == null) {
            val upload = apiClient.uploadMomentThumbnail(bytes, webp = webp, expectedUid = item.owner)
            check(upload.success && !upload.downloadUrl.isNullOrBlank()) { upload.errorMessage ?: "Lỗi tải ảnh" }
            item.thumbnailUrl = upload.downloadUrl
            item.md5 = upload.md5 ?: apiClient.computeMd5(bytes)
        }
        if (item.video != null && item.videoUrl == null) {
            check(activeAccount(item.owner)) { "Phiên đăng nhập đã thay đổi" }
            val upload = apiClient.uploadMomentVideo(item.video, expectedUid = item.owner)
            check(upload.success && !upload.downloadUrl.isNullOrBlank()) { upload.errorMessage ?: "Lỗi tải video" }
            item.videoUrl = upload.downloadUrl
        }
        check(activeAccount(item.owner)) { "Phiên đăng nhập đã thay đổi" }
        val today = todayYyyymmdd()
        val streak = today.takeIf { sessionManager.streakLastUpdatedYyyymmdd != it }
        item.attemptedPost = true
        val post = apiClient.postMoment(
            thumbnailUrl = checkNotNull(item.thumbnailUrl),
            caption = item.caption,
            recipients = item.recipients,
            md5 = checkNotNull(item.md5),
            videoUrl = item.videoUrl,
            updateStreakForYyyymmdd = streak,
            expectedUid = item.owner
        )
        check(post.success) { post.errorMessage ?: "Lỗi đăng khoảnh khắc" }
        if (streak != null && activeAccount(item.owner)) sessionManager.streakLastUpdatedYyyymmdd = streak
    }

    private fun clearMomentUploads() {
        uploadJob?.cancel()
        pendingUploads.forEach(::releaseUpload)
        pendingUploads.clear()
        failedUploads.values.forEach(::releaseUpload)
        failedUploads.clear()
        _momentUploadFailures.value = emptyList()
    }

    override fun onCleared() {
        clearMomentUploads()
        super.onCleared()
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
        val account = sessionManager.currentUser.uid
        val token = java.util.UUID.randomUUID().toString()
        val pending = PendingChat(token, receiverUid, message, System.currentTimeMillis())
        if (activeFriendUid == receiverUid && momentUid == null && activeAccount(account)) _pendingChat.update { it + pending }
        viewModelScope.launch {
            val res = apiClient.sendChatMessage(receiverUid, message, momentUid, token)
            if (!activeAccount(account)) return@launch
            if (res.isSuccess) {
                _pendingChat.update { rows -> rows.map { if (it.token == token) it.copy(acknowledged = true) else it } }
                onSuccess()
                val current = _activeConversationId.value
                if (activeFriendUid == receiverUid && current != null) {
                    if (!_chatRealtime.value) launch { fetchChatMessagesAndMarkRead(current, incremental = true) }
                    launch { refreshConversations(force = false) }
                } else if (activeFriendUid == receiverUid) {
                    openChat(receiverUid)
                } else refreshConversations()
            } else {
                _pendingChat.update { rows -> rows.filterNot { it.token == token } }
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
                chatPagination[conversationId] = page.nextPageToken
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

    /** Tai lai goi y ket ban (goi khi mo man Ban be). Loi/401 -> rong, UI an section. */
    fun refreshFriendRecommendations() {
        viewModelScope.launch {
            val dismissed = sessionManager.dismissedRecommendationUids
            apiClient.getFriendRecommendations(dismissed.toList()).onSuccess { recs ->
                if (!_isLoggedIn.value) return@onSuccess
                val myUid = sessionManager.currentUser.uid
                val friendUids = _friends.value.map { it.uid }.toSet()
                _friendRecommendations.value = recs.filter { it.uid != myUid && it.uid !in friendUids && it.uid !in dismissed }
            }
        }
    }

    /** An 1 goi y: luu uid vao dismissed (goc gui lai o lan goi sau) + bo khoi danh sach hien. */
    fun dismissRecommendation(uid: String) {
        sessionManager.dismissedRecommendationUids = sessionManager.dismissedRecommendationUids + uid
        _friendRecommendations.value = _friendRecommendations.value.filter { it.uid != uid }
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
        val owner = sessionManager.currentUser.uid
        if (owner.isBlank() || moment.userUid != owner || !activeAccount(owner)) {
            onError("Chỉ được xóa ảnh của chính bạn")
            return
        }
        viewModelScope.launch {
            val res = apiClient.deleteMoment(moment.userUid, moment.id, deleteGlobally)
            if (!activeAccount(owner)) return@launch
            if (res.getOrNull() == true) {
                _momentViewCounts.update { it - moment.id }
                _momentViewers.update { it - moment.id }
                _momentViewErrors.update { it - moment.id }
                deletedMomentIds.add(moment.id)
                removeMomentsFromCache { it.id == moment.id }
                refreshFeed()
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

    suspend fun readGeneralAccount() = apiClient.fetchAccountDetails()

    suspend fun changeGeneralEmail(email: String): Result<Unit> {
        val result = apiClient.changeAccountEmail(email)
        if (result.isSuccess) {
            sessionManager.savedEmail = email.trim()
            refreshProfile()
        }
        return result
    }

    suspend fun requestGeneralPhoneCode(phone: String) = apiClient.requestEditedPhoneCode(phone)
    suspend fun changeGeneralPhone(phone: String, code: String) = apiClient.verifyEditedPhone(phone, code)
    suspend fun changeGeneralBirthday(month: Int, day: Int) = apiClient.changeBirthday(month, day)

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
        clearMomentUploads()
        syncAgain = false
        syncJob?.cancel()
        feedListenJob?.cancel(); feedListenJob = null
        friendsListenJob?.cancel(); friendsListenJob = null
        conversationsListenJob?.cancel(); conversationsListenJob = null
        friendsLive = false; conversationsLive = false
        _feedRealtime.value = false
        _feedLoading.value = false
        _feedError.value = null
        closeChat()
        apiClient.clearUserNameCache()
        prefetchedThumbnails.clear()
        videoPrefetchJobs.values.toList().forEach { it.cancel() }
        videoPrefetchJobs.clear(); videoPrefetched.clear()
        viewedMomentUids.clear()
        _momentViewCounts.value = emptyMap()
        _momentViewers.value = emptyMap()
        _momentViewErrors.value = emptyMap()
        _momentViewsLoading.value = emptySet()
        lastConversationSync = 0L
        chatRefreshedAt.clear()
        chatPagination.clear()
        chatMarkedThrough.clear()
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
        _pendingChat.value = emptyList()
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
