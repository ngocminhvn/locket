package com.locket.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.locket.app.ui.components.LocketTopBar
import com.locket.app.ui.components.locketHeaderTransition
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.locket.app.api.LocketApiClient
import com.locket.app.model.Friend
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.FeedBottomBar
import com.locket.app.ui.components.LocketProfileAvatar
import com.locket.app.ui.screens.*
import com.locket.app.ui.glass.LocketNavigationGlassTheme
import com.locket.app.ui.theme.LocketBlack
import com.locket.app.ui.theme.LocketTheme

class MainActivity : ComponentActivity() {
    // Start only provider initialization, not a hardware camera session.
    // CameraScreen still binds/unbinds use cases according to its lifecycle.
    private fun prewarmCameraProvider() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            ProcessCameraProvider.getInstance(applicationContext)
        } catch (e: Exception) {
            Log.w("LocketCamera", "Camera provider warm-up unavailable", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionManager = (application as LocketApplication).sessionManager
        if (sessionManager.isLoggedIn) prewarmCameraProvider()
        val apiClient = LocketApiClient(
            getToken = { sessionManager.idToken },
            getUid = { sessionManager.currentUser.uid },
            getRefreshToken = { sessionManager.refreshToken },
            isTokenStale = { sessionManager.isTokenStale },
            onTokensRefreshed = { idToken, refreshToken ->
                sessionManager.idToken = idToken
                sessionManager.refreshToken = refreshToken
            }
        )
        val viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LocketViewModel(sessionManager, apiClient, (application as LocketApplication).cache) as T
        })[LocketViewModel::class.java]

        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        })
        setContent {
            LocketTheme {
                val isLoggedIn by viewModel.isLoggedInFlow.collectAsState()
                val uploadFailures by viewModel.momentUploadFailures.collectAsState()
                // Widget mo app dung man Home: intent extra "open_home" (LocketWidgetUpdater).
                val openHome = intent.getBooleanExtra("open_home", false)
                var currentScreen by remember { mutableStateOf(if (isLoggedIn) (if (openHome) "feed" else "camera") else "welcome") }
                var showFriendsModal by remember { mutableStateOf(false) }
                var showProfileScreen by remember { mutableStateOf(false) }
                var selectedGridIndex by remember { mutableStateOf(0) }
                var activeChatFriend by remember { mutableStateOf<Friend?>(null) }
                var homeTabSwipeEnabled by remember { mutableStateOf(true) }
                val mainTab = when (currentScreen) {
                    "history" -> 0
                    "camera", "feed", "grid" -> 1
                    "chat" -> 2
                    else -> -1
                }
                val tabStateHolder = rememberSaveableStateHolder()
                val tabRoutes = remember { listOf("history", "camera", "chat") }

                LaunchedEffect(isLoggedIn) {
                    if (!isLoggedIn) {
                        currentScreen = "welcome"
                        tabStateHolder.removeState("history")
                        tabStateHolder.removeState("chat")
                    } else prewarmCameraProvider()
                }

                // Android Back (button or system edge gesture): secondary screens
                // return to the main camera. At the camera let Android handle
                // Back normally. HomePagerScreen handles feed pages itself;
                // child dialogs and camera review keep their own Back priority.
                BackHandler(
                    enabled = showProfileScreen || showFriendsModal ||
                        currentScreen == "login" ||
                        (isLoggedIn && currentScreen !in listOf("camera", "feed", "welcome"))
                ) {
                    if (!isLoggedIn) {
                        showProfileScreen = false
                        showFriendsModal = false
                        currentScreen = "welcome"
                    } else {
                        showProfileScreen = false
                        showFriendsModal = false
                        activeChatFriend = null
                        selectedGridIndex = 0
                        homeTabSwipeEnabled = true
                        viewModel.setReplyToFriend(null)
                        viewModel.selectFriend(null)
                        currentScreen = "camera"
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocketBlack)
                ) {
                    when {
                        showProfileScreen -> {
                            ProfileScreen(
                                viewModel = viewModel,
                                onBack = { showProfileScreen = false }
                            )
                        }
                        showFriendsModal -> {
                            FriendsScreen(
                                viewModel = viewModel,
                                onClose = { showFriendsModal = false }
                            )
                        }
                        currentScreen in listOf("history", "camera", "feed", "chat") -> {
                            val pager = rememberPagerState(initialPage = mainTab.coerceIn(0, 2)) { 3 }
                            val activeRoute by rememberUpdatedState(currentScreen)
                            var homeNavVisible by remember { mutableStateOf(true) }
                            var homeNavFeed by remember { mutableStateOf(false) }
                            var homeVerticalProgress by remember { mutableFloatStateOf(1f) }
                            var homeCameraRequest by remember { mutableIntStateOf(0) }
                            var homeShareRequest by remember { mutableIntStateOf(0) }
                            var homeFilterOpen by remember { mutableStateOf(false) }
                            var newChatOpen by remember { mutableStateOf(false) }
                            val headerProgress = remember(pager) {
                                { (1f - 2f * kotlin.math.abs(pager.currentPageOffsetFraction)).coerceIn(0f, 1f) }
                            }
                            val homeChromeProgress = remember(pager) {
                                { (1f - 2f * kotlin.math.abs(pager.currentPage + pager.currentPageOffsetFraction - 1f)).coerceIn(0f, 1f) }
                            }
                            val profile by viewModel.currentUser.collectAsState()
                            val friends by viewModel.friends.collectAsState()
                            val selectedFriend by viewModel.selectedFriend.collectAsState()
                            LaunchedEffect(pager.currentPage) {
                                if (pager.currentPage != 1) homeFilterOpen = false
                                if (pager.currentPage != 2) newChatOpen = false
                            }
                            // Programmatic taps and Android Back use the same pager motion.
                            LaunchedEffect(mainTab) {
                                if (pager.currentPage != mainTab || pager.currentPageOffsetFraction != 0f) {
                                    pager.animateScrollToPage(mainTab,
                                        animationSpec = tween(230, easing = FastOutSlowInEasing))
                                }
                            }
                            // Do not write navigation state mid-drag; update once settled.
                            LaunchedEffect(pager) {
                                snapshotFlow { pager.settledPage }.drop(1).collect { page ->
                                    val route = activeRoute
                                    val routeTab = when (route) {
                                        "history" -> 0
                                        "camera", "feed" -> 1
                                        "chat" -> 2
                                        else -> -1
                                    }
                                    if (routeTab >= 0 && page != routeTab) {
                                        if (page == 1) {
                                            viewModel.setReplyToFriend(null)
                                            viewModel.selectFriend(null)
                                            selectedGridIndex = 0
                                            homeTabSwipeEnabled = true
                                        }
                                        currentScreen = tabRoutes[page]
                                    }
                                }
                            }
                            // One shared backdrop recording for the main navigation only.
                            // Keep the existing pager, chrome bounds and touch handling.
                            LocketNavigationGlassTheme(background = LocketBlack) {
                            HorizontalPager(
                                state = pager,
                                modifier = Modifier.fillMaxSize(),
                                beyondViewportPageCount = 0,
                                userScrollEnabled = pager.settledPage != 1 || homeTabSwipeEnabled,
                                flingBehavior = PagerDefaults.flingBehavior(
                                    state = pager,
                                    snapAnimationSpec = tween(230, easing = FastOutSlowInEasing)
                                ),
                                overscrollEffect = null
                            ) { page ->
                                when (page) {
                                    0 -> tabStateHolder.SaveableStateProvider("history") {
                                        HistoryScreen(viewModel = viewModel,
                                            onNavigate = { currentScreen = it },
                                            onOpenProfile = { showProfileScreen = true }, sharedProfileAvatar = true)
                                    }
                                    1 -> {
                                        // Only bind the camera when its page is actually being displayed.
                                        // Prefetched but invisible pages must not keep the hardware open.
                                        if (pager.currentPage == 1 || pager.settledPage == 1 || pager.targetPage == 1) {
                                            HomePagerScreen(
                                                viewModel = viewModel,
                                                startOnCamera = currentScreen != "feed",
                                                initialMomentIndex = selectedGridIndex,
                                                onNavigate = { destination ->
                                                    if (destination == "profile") showProfileScreen = true
                                                    else currentScreen = destination
                                                },
                                                onOpenFriends = { showFriendsModal = true },
                                                onOpenProfile = { showProfileScreen = true },
                                                onTabSwipeEnabledChange = { enabled ->
                                                    if (pager.settledPage == 1 || pager.currentPage == 1) homeTabSwipeEnabled = enabled
                                                },
                                                tabActive = pager.settledPage == 1,
                                                onNavigationState = { visible, feed ->
                                                    homeNavVisible = visible
                                                    homeNavFeed = feed
                                                },
                                                cameraRequest = homeCameraRequest,
                                                shareRequest = homeShareRequest,
                                                sharedProfileAvatar = true,
                                                sharedFriendFilterOpen = homeFilterOpen,
                                                onSharedFriendFilterChange = { homeFilterOpen = it },
                                                tabTransitionProgress = homeChromeProgress,
                                                onVerticalChromeProgress = { homeVerticalProgress = it }
                                            )
                                        }
                                    }
                                    2 -> tabStateHolder.SaveableStateProvider("chat") {
                                        ConversationsScreen(viewModel = viewModel,
                                            onNavigate = { currentScreen = it },
                                            onOpenChatDetail = { friend ->
                                                activeChatFriend = friend
                                                currentScreen = "chat_detail"
                                            },
                                            onOpenProfile = { showProfileScreen = true }, sharedProfileAvatar = true,
                                            sharedNewChatOpen = newChatOpen,
                                            onSharedNewChatChange = { newChatOpen = it })
                                    }
                                }
                            }
                            // Render one navigation bar outside the horizontal pager.
                            // Its bounds never move with a page, even while the user drags.
                            val homeSettled = pager.settledPage == 1
                            // All top chrome stays in screen coordinates, not pager coordinates.
                            val headerPage = pager.currentPage
                            // Fade out the outgoing header to zero at the midpoint,
                            // then fade in the incoming one at the same fixed anchors.
                            // The avatar deliberately never uses this layer.
                            if (headerPage != 1 || homeNavVisible || homeFilterOpen) {
                                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                                    if (headerPage == 1) {
                                        LocketTopBar(
                                            title = if (!homeNavFeed) "${friends.size} Bạn bè"
                                                else if (selectedFriend?.uid == profile.uid) "Bạn"
                                                else selectedFriend?.displayName?.trim()?.substringAfterLast(' ')?.ifBlank { "Tất cả bạn bè" } ?: "Tất cả bạn bè",
                                            showDropdownArrow = homeNavFeed,
                                            onSpeakerClick = { if (!pager.isScrollInProgress) viewModel.syncAllData() },
                                            onTitleClick = { if (!pager.isScrollInProgress) {
                                                if (homeVerticalProgress >= 0.999f) {
                                                    if (homeNavFeed) homeFilterOpen = !homeFilterOpen else showFriendsModal = true
                                                }
                                            } },
                                            dropdownExpanded = homeFilterOpen,
                                            adaptiveBackground = homeNavFeed,
                                            showAvatar = false,
                                            transitionProgress = { headerProgress() * homeVerticalProgress }
                                        )
                                    } else {
                                        Box(Modifier.fillMaxWidth().statusBarsPadding()
                                            .padding(horizontal = 24.dp, vertical = 10.dp).height(46.dp)) {
                                            Text(if (headerPage == 0) "Kỷ niệm" else "Trò chuyện",
                                                color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.align(Alignment.Center).locketHeaderTransition(headerProgress))
                                        }
                                    }
                                    LocketProfileAvatar(profile.profilePictureUrl, { showProfileScreen = true },
                                        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 24.dp, top = 10.dp))
                                }
                            }
                            if (!homeSettled || homeNavVisible) {
                                val conversations by viewModel.conversations.collectAsState()
                                // Original Locket counts unread conversations, not messages.
                                val unreadConversations = remember(conversations) {
                                    conversations.count { it.unreadCount > 0 }
                                }
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                                    FeedBottomBar(
                                        onGridClick = { if (!pager.isScrollInProgress) currentScreen = "grid" },
                                        onHistoryClick = { currentScreen = "history" },
                                        onShutterClick = {
                                            if (homeSettled && mainTab == 1 && !pager.isScrollInProgress) {
                                                homeCameraRequest++
                                            } else {
                                                viewModel.setReplyToFriend(null)
                                                viewModel.selectFriend(null)
                                                currentScreen = "camera"
                                            }
                                        },
                                        onChatClick = { currentScreen = "chat" },
                                        onShareClick = { if (homeSettled && !pager.isScrollInProgress) homeShareRequest++ },
                                        separated = true,
                                        currentScreen = tabRoutes[mainTab],
                                        showSideActions = headerPage == 1 && homeNavFeed,
                                        unreadConversations = unreadConversations,
                                        showNewChatAction = headerPage == 2,
                                        onNewChatClick = { newChatOpen = true },
                                        sideTransitionProgress = { headerProgress() * if (headerPage == 1) homeVerticalProgress else 1f },
                                        sideActionsEnabled = !pager.isScrollInProgress && (headerPage != 1 || homeVerticalProgress >= 0.999f)
                                    )
                                }
                            }
                            if (homeFilterOpen && headerPage == 1) {
                                val filterFriends = remember(friends, profile) {
                                    val own = profile.takeIf { it.uid.isNotBlank() }?.let {
                                        Friend(uid = it.uid, username = it.username, displayName = "Bạn", avatarUrl = it.profilePictureUrl)
                                    }
                                    listOfNotNull(own) + friends.filter { it.uid != profile.uid }.distinctBy { it.uid }
                                }
                                Box(Modifier.fillMaxSize()) {
                                    FeedFriendPopup(
                                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 74.dp),
                                        friends = filterFriends, selectedFriend = selectedFriend,
                                        onSelectFriend = { viewModel.selectFriend(it); homeFilterOpen = false },
                                        onDismiss = { homeFilterOpen = false }
                                    )
                                }
                            }
                            } // LocketNavigationGlassTheme
                        }
                        else -> {
                            when (currentScreen) {
                                "welcome" -> {
                                    WelcomeScreen(
                                        onNavigateToLogin = { currentScreen = "login" }
                                    )
                                }
                                "login" -> {
                                    LoginScreen(
                                        viewModel = viewModel,
                                        onBack = { currentScreen = "welcome" },
                                        onLoginSuccess = { currentScreen = "camera" }
                                    )
                                }
                                "chat_detail" -> {
                                    activeChatFriend?.let { friend ->
                                        ChatDetailScreen(
                                            viewModel = viewModel,
                                            friend = friend,
                                            onBack = { currentScreen = "chat" }
                                        )
                                    } ?: run {
                                        currentScreen = "chat"
                                    }
                                }
                                "grid" -> {
                                    HomeGridScreen(
                                        viewModel = viewModel,
                                        onMomentClick = { index ->
                                            selectedGridIndex = index
                                            currentScreen = "feed"
                                        },
                                        onNavigate = { currentScreen = it },
                                        onOpenFriends = { showFriendsModal = true },
                                        onOpenProfile = { showProfileScreen = true }
                                    )
                                }
                            }
                        }
                    }
                    if (isLoggedIn) uploadFailures.firstOrNull()?.let { failure ->
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                            tonalElevation = 6.dp
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Gửi khoảnh khắc thất bại")
                                Text(failure.message.take(180))
                                if (failure.mayHavePosted) Text("Kiểm tra lịch sử trước khi gửi lại để tránh đăng trùng.")
                                Row(Modifier.align(Alignment.End)) {
                                    TextButton(onClick = { viewModel.discardMomentUpload(failure.id) }) { Text("Bỏ") }
                                    TextButton(onClick = { viewModel.retryMomentUpload(failure.id) }) { Text(if (failure.mayHavePosted) "Vẫn gửi lại" else "Gửi lại") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
