package com.locket.app

import android.os.Bundle
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
import com.locket.app.ui.screens.*
import com.locket.app.ui.theme.LocketBlack
import com.locket.app.ui.theme.LocketTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionManager = (application as LocketApplication).sessionManager
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
                    }
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
                            var homeCameraRequest by remember { mutableIntStateOf(0) }
                            var homeShareRequest by remember { mutableIntStateOf(0) }
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
                                            onOpenProfile = { showProfileScreen = true })
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
                                                shareRequest = homeShareRequest
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
                                            onOpenProfile = { showProfileScreen = true })
                                    }
                                }
                            }
                            // Render one navigation bar outside the horizontal pager.
                            // Its bounds never move with a page, even while the user drags.
                            val homeSettled = pager.settledPage == 1
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
                                        currentScreen = tabRoutes[pager.settledPage],
                                        showSideActions = homeSettled && homeNavFeed,
                                        unreadConversations = unreadConversations
                                    )
                                }
                            }
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
