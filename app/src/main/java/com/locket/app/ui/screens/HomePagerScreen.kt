package com.locket.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
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
    val moments = remember(allMoments, selectedFriend) {
        allMoments.filter { selectedFriend == null || it.userUid == selectedFriend?.uid }
            .sortedByDescending { it.timestampSeconds }
    }
    // Keep an honest empty-feed page while the real cache/network is loading.
    val pagerState = rememberPagerState(
        initialPage = if (startOnCamera) 0 else initialMomentIndex.coerceIn(0, (moments.size - 1).coerceAtLeast(0)) + 1,
        pageCount = { 1 + moments.size.coerceAtLeast(1) }
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

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage == 0) viewModel.selectFriend(null)
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
