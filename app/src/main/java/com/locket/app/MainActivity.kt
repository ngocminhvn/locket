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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.locket.app.api.LocketApiClient
import com.locket.app.model.Friend
import com.locket.app.ui.LocketViewModel
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
        val viewModel = LocketViewModel(sessionManager, apiClient, (application as LocketApplication).cache)

        setContent {
            LocketTheme {
                val isLoggedIn by viewModel.isLoggedInFlow.collectAsState()
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
                                                tabActive = pager.settledPage == 1
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
                }
            }
        }
    }
}
