package com.locket.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch
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
    onOpenProfile: () -> Unit
) {
    val allMoments by viewModel.moments.collectAsState()
    val selectedFriend by viewModel.selectedFriend.collectAsState()
    val loading by viewModel.historyLoading.collectAsState()
    val hasMore by viewModel.historyHasMore.collectAsState()
    val error by viewModel.historyError.collectAsState()
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
        else moments.getOrNull(pagerState.settledPage - 1)?.let { viewModel.markMomentViewed(it.id) }
    }

    // Prefetch anh cua cac moment ke tiep truoc khi user vuot toi, de anh
    // da nam trong disk cache cua Coil luc can hien (xem LocketViewModel).
    // page 1 = moments[0]; page 0 (camera) thi prefetch tu moment moi nhat.
    LaunchedEffect(pagerState.settledPage, moments) {
        viewModel.prefetchUpcomingMoments(moments, pagerState.settledPage - 1)
    }

    // Prefetch before the last cached image; a dedicated trailing page stays
    // available if the server is still loading or a filtered page has no match.
    LaunchedEffect(pagerState.currentPage, moments.size, loading, hasMore, error) {
        if (pagerState.currentPage > 0 && pagerState.currentPage >= (moments.size - 3).coerceAtLeast(1) &&
            hasMore && !loading && error == null) {
            viewModel.loadOlderMoments()
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

    BackHandler(enabled = pagerState.currentPage != 0) {
        viewModel.setReplyToFriend(null)
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    VerticalPager(
        state = pagerState,
        userScrollEnabled = pagerState.currentPage != 0 || cameraSwipeEnabled,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            // Native upward swipe reveals the next page. Also accept pulling
            // downward at the camera's top boundary, as requested, without
            // stealing taps or the pager's own upward drag.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val fromCamera = allowDownGesture
                var delta = androidx.compose.ui.geometry.Offset.Zero
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    delta = pointer.position - down.position
                    if (!pointer.pressed) {
                        if (fromCamera && allowDownGesture &&
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
            CameraScreen(
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
                onSwipeEnabledChange = { cameraSwipeEnabled = it }
            )
        } else if (page > moments.size) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) CircularProgressIndicator(color = com.locket.app.ui.theme.LocketYellow)
                    Text(error ?: if (hasMore) "Đang tải ảnh cũ..." else "Chưa có khoảnh khắc nào", color = Color.White)
                    if (error != null) TextButton(onClick = { viewModel.loadOlderMoments() }) { Text("Thử lại") }
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
                onOpenProfile = onOpenProfile
            )
        }
    }
}
