package com.locket.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.locket.app.ui.components.LocketTopBar
import com.locket.app.ui.components.LocketBottomBar
import com.locket.app.ui.components.FeedBottomBar
import com.locket.app.ui.components.FriendFilterSheet
import com.locket.app.ui.theme.LocketBlack
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.locket.app.ui.LocketViewModel
import com.locket.app.model.Friend
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

/**
 * One continuous vertical home: camera, newest moment, then older moments.
 * Native pager scrolling follows the finger, rather than replacing destinations.
 * The calendar is a separate destination accessed by its navigation button.
 */
@Composable
fun HomePagerScreen(
    viewModel: LocketViewModel,
    startOnCamera: Boolean = false,
    initialMomentIndex: Int = 0,
    onNavigate: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit,
    onTabSwipeEnabledChange: (Boolean) -> Unit = {},
    tabActive: Boolean = true
) {
    val allMoments by viewModel.moments.collectAsState()
    val selectedFriend by viewModel.selectedFriend.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val feedLoading by viewModel.historyLoading.collectAsState()
    val feedHasMore by viewModel.historyHasMore.collectAsState()
    val feedError by viewModel.historyError.collectAsState()
    val ownLoading by viewModel.ownHistoryLoading.collectAsState()
    val ownHasMore by viewModel.ownHistoryHasMore.collectAsState()
    val ownError by viewModel.ownHistoryError.collectAsState()
    val ownSelected = currentUser.uid.isNotBlank() && selectedFriend?.uid == currentUser.uid
    val loading = if (ownSelected) ownLoading else feedLoading
    val hasMore = if (ownSelected) ownHasMore else feedHasMore
    val error = if (ownSelected) ownError else feedError
    val filterFriends = remember(friends, currentUser) {
        val own = currentUser.takeIf { it.uid.isNotBlank() }?.let {
            Friend(uid = it.uid, username = it.username, displayName = "Bạn", avatarUrl = it.profilePictureUrl)
        }
        listOfNotNull(own) + friends.filter { it.uid != currentUser.uid }.distinctBy { it.uid }
    }
    fun loadFilteredHistory() {
        if (ownSelected) viewModel.loadOwnHistory() else viewModel.loadOlderMoments()
    }
    val moments = remember(allMoments, selectedFriend) {
        allMoments.filter { selectedFriend == null || it.userUid == selectedFriend?.uid }
            .sortedByDescending { it.timestampSeconds }
    }
    // Keep an honest empty-feed page while the real cache/network is loading.
    val pagerState = rememberPagerState(
        initialPage = if (startOnCamera) 0 else initialMomentIndex.coerceIn(0, (moments.size - 1).coerceAtLeast(0)) + 1,
        pageCount = { 1 + moments.size + if (hasMore || moments.isEmpty()) 1 else 0 }
    )
    val scope = rememberCoroutineScope()
    var cameraSwipeEnabled by remember { mutableStateOf(true) }
    var previousFilter by remember { mutableStateOf(selectedFriend?.uid) }
    var cameraReviewVisible by remember { mutableStateOf(false) }
    var showFriendFilter by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var zoomLocked by remember { mutableStateOf(false) }
    var reactionEvent by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reactionSerial by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val statusPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var measuredHeader by remember { mutableStateOf(0.dp) }
    var measuredCameraFooter by remember { mutableStateOf(0.dp) }
    var measuredFeedFooter by remember { mutableStateOf(0.dp) }
    val headerHeight = measuredHeader.takeIf { it > 0.dp } ?: (statusPadding + 66.dp)
    val cameraFooterHeight = measuredCameraFooter.takeIf { it > 0.dp } ?: (navPadding + 70.dp)
    val feedFooterHeight = measuredFeedFooter.takeIf { it > 0.dp } ?: (navPadding + 150.dp)
    // Keep shared controls stationary until a page settles. During a drag
    // only the photo, caption and author scroll with the finger.
    val cameraSelected = pagerState.settledPage == 0
    val activeMoment = moments.getOrNull(pagerState.settledPage - 1)
    val showChrome = !(cameraSelected && cameraReviewVisible) && !zoomLocked
    LaunchedEffect(pagerState.settledPage) { zoomLocked = false; showShare = false }
    BackHandler(enabled = showFriendFilter) { showFriendFilter = false }
    SideEffect {
        onTabSwipeEnabledChange(!showFriendFilter && !showShare && !zoomLocked && !pagerState.isScrollInProgress &&
            (!cameraSelected || (cameraSwipeEnabled && !cameraReviewVisible)))
    }

    LaunchedEffect(selectedFriend?.uid) {
        val uid = selectedFriend?.uid
        if (previousFilter != uid) {
            previousFilter = uid
            if (pagerState.currentPage != 0) pagerState.scrollToPage(1)
        }
    }

    // Page 0 = camera, page 1..moments.size = moment. Khi settled sang 1
    // moment thi danh dau da xem (markMomentAsViewed, goc dedupe theo
    // viewedMomentUids — xem LocketViewModel.markMomentViewed). Them moments
    // vao key de van danh dau khi feed ve sau luc page da settled (vd mo app
    // luc cache trong); dedupe ben ViewModel nen goi lai cung khong gui trung.
    LaunchedEffect(pagerState.settledPage, moments) {
        if (pagerState.settledPage == 0) viewModel.selectFriend(null)
        else moments.getOrNull(pagerState.settledPage - 1)?.takeIf { it.userUid != currentUser.uid }?.let { viewModel.markMomentViewed(it.id) }
    }

    // Prefetch anh cua cac moment ke tiep truoc khi user vuot toi, de anh
    // da nam trong disk cache cua Coil luc can hien (xem LocketViewModel).
    // page 1 = moments[0]; page 0 (camera) thi prefetch tu moment moi nhat.
    LaunchedEffect(pagerState, moments) {
        var previous = pagerState.currentPage
        snapshotFlow { pagerState.currentPage to pagerState.targetPage }
            .collect { (current, target) ->
                val forward = if (target != current) target > current else current >= previous
                viewModel.prefetchUpcomingMoments(moments, current - 1, count = 8, forward = forward)
                if (target != current) viewModel.prefetchUpcomingMoments(moments, target - 1, count = 5, forward = forward)
                previous = current
            }
    }

    // Prefetch before the last cached image; a dedicated trailing page stays
    // available if the server is still loading or a filtered page has no match.
    LaunchedEffect(pagerState.currentPage, moments.size, loading, hasMore, error, ownSelected) {
        if (pagerState.currentPage > 0 && pagerState.currentPage >= (moments.size - 7).coerceAtLeast(1) &&
            hasMore && !loading && error == null) {
            loadFilteredHistory()
        }
    }

    fun openLatest() {
        if (pagerState.currentPage == 0 && !cameraSwipeEnabled) return
        viewModel.selectFriend(null)
        scope.launch { pagerState.animateScrollToPage(1) }
    }

    val latestAction by rememberUpdatedState<() -> Unit>({ openLatest() })
    val allowDownGesture by rememberUpdatedState(
        pagerState.currentPage == 0 && pagerState.settledPage == 0 && cameraSwipeEnabled
    )

    BackHandler(enabled = tabActive && pagerState.currentPage != 0) {
        viewModel.setReplyToFriend(null)
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    Box(Modifier.fillMaxSize().background(LocketBlack).clipToBounds()) {
    VerticalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        overscrollEffect = null,
        key = { page -> if (page == 0) "camera" else moments.getOrNull(page - 1)?.id ?: "loading-tail" },
        userScrollEnabled = !zoomLocked && !showFriendFilter && (pagerState.settledPage != 0 || cameraSwipeEnabled),
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            // Native upward swipe reveals the next page. Also accept pulling
            // downward at the camera's top boundary, as requested, without
            // stealing taps or the pager's own upward drag.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val fromCamera = allowDownGesture
                var delta = androidx.compose.ui.geometry.Offset.Zero
                var multiTouch = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2 || event.changes.any { it.id != down.id }) multiTouch = true
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    delta = pointer.position - down.position
                    if (!pointer.pressed) {
                        if (!multiTouch && fromCamera && allowDownGesture &&
                            delta.y > 52.dp.toPx() && delta.y > abs(delta.x) * 1.3f) {
                            latestAction()
                        }
                        break
                    }
                } while (true)
            }
        }
    ) { page ->
        if (page == 0) {
            // Pager can compose camera as an offscreen page. Don't bind
            // CameraX until the user is at (or approaching) camera.
            if (pagerState.currentPage <= 1 || pagerState.targetPage == 0 || !cameraSwipeEnabled) CameraScreen(
                viewModel = viewModel,
                onNavigate = { destination ->
                    when (destination) {
                        "latest" -> openLatest()
                        "feed" -> {
                            viewModel.selectFriend(null)
                            scope.launch { pagerState.animateScrollToPage(1) }
                        }
                        "camera" -> Unit
                        else -> onNavigate(destination)
                    }
                },
                onOpenFriends = onOpenFriends,
                onOpenProfile = onOpenProfile,
                onSwipeEnabledChange = { cameraSwipeEnabled = it },
                sharedHeaderHeight = headerHeight,
                sharedFooterHeight = cameraFooterHeight,
                onReviewVisibleChange = { cameraReviewVisible = it }
            )
        } else if (page > moments.size) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) CircularProgressIndicator(color = com.locket.app.ui.theme.LocketYellow)
                    Text(error ?: if (hasMore) "Đang tải ảnh cũ..." else "Chưa có khoảnh khắc nào", color = Color.White)
                    if (error != null) TextButton(onClick = { loadFilteredHistory() }) { Text("Thử lại") }
                    TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(0) } }) { Text("Về camera") }
                }
            }
        } else {
            HomeMomentScreen(
                viewModel = viewModel,
                currentMoment = moments.getOrNull(page - 1),
                onNavigate = { destination ->
                    if (destination == "camera") {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    } else onNavigate(destination)
                },
                onOpenFriends = onOpenFriends,
                onOpenProfile = onOpenProfile,
                headerHeight = headerHeight,
                footerHeight = feedFooterHeight,
                reactionEvent = reactionEvent,
                reactionSerial = reactionSerial,
                isActive = pagerState.settledPage == page && !pagerState.isScrollInProgress,
                onZoomChange = { if (pagerState.settledPage == page) zoomLocked = it },
                openActionsRequested = showShare && pagerState.settledPage == page,
                onActionsRequestConsumed = { showShare = false }
            )
        }
    }

    // One header and one footer for the entire pager, never duplicated.
    if (showChrome) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { measuredHeader = with(density) { it.height.toDp() } }
        ) {
            LocketTopBar(
                title = if (cameraSelected) "${friends.size} Bạn bè"
                    else if (selectedFriend?.uid == currentUser.uid) "Bạn"
                    else selectedFriend?.displayName?.trim()?.substringAfterLast(' ')?.ifBlank { "Tất cả bạn bè" } ?: "Tất cả bạn bè",
                showDropdownArrow = !cameraSelected,
                avatarUrl = currentUser.profilePictureUrl,
                onSpeakerClick = { viewModel.syncAllData() },
                onTitleClick = { if (cameraSelected) onOpenFriends() else showFriendFilter = !showFriendFilter },
                dropdownExpanded = showFriendFilter,
                adaptiveBackground = !cameraSelected,
                onAvatarClick = onOpenProfile
            )
        }

        if (cameraSelected) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { measuredCameraFooter = with(density) { it.height.toDp() } }) {
                LocketBottomBar("camera") { destination ->
                    if (destination != "camera") onNavigate(destination)
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { measuredFeedFooter = with(density) { it.height.toDp() } },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (activeMoment != null && activeMoment.userUid == currentUser.uid) {
                    OwnMomentActivityBar(viewModel, activeMoment)
                } else {
                    MomentReactionBar(viewModel, activeMoment) { emoji ->
                        activeMoment?.let {
                            reactionEvent = it.id to emoji
                            reactionSerial++
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                FeedBottomBar(
                    onGridClick = { onNavigate("grid") },
                    onHistoryClick = { onNavigate("history") },
                    onShutterClick = {
                        viewModel.setReplyToFriend(null)
                        scope.launch { pagerState.animateScrollToPage(0) }
                    },
                    onChatClick = { onNavigate("chat") },
                    onShareClick = { if (activeMoment != null && !pagerState.isScrollInProgress) showShare = true },
                    separated = true
                )
            }
        }
    }
    if (showFriendFilter) {
        FeedFriendPopup(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = headerHeight + 8.dp),
            friends = filterFriends,
            selectedFriend = selectedFriend,
            onSelectFriend = { viewModel.selectFriend(it); showFriendFilter = false },
            onDismiss = { showFriendFilter = false }
        )
    }
    }
}
