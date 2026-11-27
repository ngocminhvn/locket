package com.locket.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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
                // Retain saveable history/chat UI state, not live camera resources.
                val tabStateHolder = rememberSaveableStateHolder()
                val density = LocalDensity.current
                var previousTab by remember { mutableIntStateOf(mainTab) }
                val tabDirection = remember(mainTab) {
                    if (previousTab >= 0 && mainTab >= 0) (mainTab - previousTab).coerceIn(-1, 1) else 0
                }
                val tabMotion = remember(mainTab) { Animatable(if (tabDirection == 0) 0f else 1f) }
                SideEffect { previousTab = mainTab }
                LaunchedEffect(tabMotion) {
                    tabMotion.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow))
                }
                val canSwipeTabs by rememberUpdatedState(
                    isLoggedIn && mainTab >= 0 && !showProfileScreen && !showFriendsModal &&
                        (currentScreen !in listOf("camera", "feed") || homeTabSwipeEnabled)
                )
                val activeTab by rememberUpdatedState(mainTab)
                val switchTab by rememberUpdatedState<(Int) -> Unit>({ tab ->
                    if (tab in 0..2 && tab != mainTab) {
                        if (tab == 1) {
                            viewModel.setReplyToFriend(null)
                            viewModel.selectFriend(null)
                            selectedGridIndex = 0
                            homeTabSwipeEnabled = true
                        }
                        currentScreen = listOf("history", "camera", "chat")[tab]
                    }
                })

                LaunchedEffect(isLoggedIn) {
                    if (!isLoggedIn) {
                        currentScreen = "welcome"
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
                        .pointerInput(Unit) {
                            // Edge-inward swipe: right from the left edge goes
                            // to the previous tab; left from the right goes to
                            // the next. Leave the outermost system-back area
                            // to Android and ignore vertical/diagonal drags.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val startTab = activeTab
                                val edgeWidth = 80.dp.toPx()
                                val systemEdge = 18.dp.toPx()
                                val fromLeft = down.position.x >= systemEdge && down.position.x <= edgeWidth
                                val fromRight = down.position.x <= size.width - systemEdge &&
                                    down.position.x >= size.width - edgeWidth
                                val target = when {
                                    fromLeft && startTab > 0 -> startTab - 1
                                    fromRight && startTab in 0..1 -> startTab + 1
                                    else -> -1
                                }
                                if (!canSwipeTabs || target < 0) return@awaitEachGesture
                                var locked = false
                                var completed = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } > 1 ||
                                        !canSwipeTabs || activeTab != startTab) break
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val delta = change.position - down.position
                                    val horizontal = if (fromLeft) delta.x else -delta.x
                                    if (!locked) {
                                        if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                                        if (horizontal < -viewConfiguration.touchSlop) break
                                        if (horizontal > maxOf(viewConfiguration.touchSlop, 16.dp.toPx()) &&
                                            horizontal > abs(delta.y) * 1.5f) locked = true
                                    }
                                    if (locked) {
                                        change.consume()
                                        if (!change.pressed) completed = horizontal >= 64.dp.toPx()
                                    }
                                    if (!change.pressed) break
                                }
                                if (completed && canSwipeTabs && activeTab == startTab) switchTab(target)
                            }
                        }
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
                        else -> {
                            // Animate only the incoming surface. Keeping two live
                            // screen trees during a transition is expensive and can
                            // overlap camera bind/unbind operations.
                            Box(Modifier.fillMaxSize().graphicsLayer {
                                val progress = tabMotion.value.coerceIn(0f, 1f)
                                translationX = with(density) { 28.dp.toPx() } * tabDirection * progress
                                alpha = 1f - 0.12f * progress
                            }) {
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
                                "camera", "feed" -> {
                                    HomePagerScreen(
                                        viewModel = viewModel,
                                        startOnCamera = currentScreen == "camera",
                                        initialMomentIndex = selectedGridIndex,
                                        onNavigate = { destination ->
                                            if (destination == "profile") showProfileScreen = true
                                            else currentScreen = destination
                                        },
                                        onOpenFriends = { showFriendsModal = true },
                                        onOpenProfile = { showProfileScreen = true },
                                        onTabSwipeEnabledChange = { homeTabSwipeEnabled = it }
                                    )
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
                                "history" -> tabStateHolder.SaveableStateProvider("history") {
                                    HistoryScreen(
                                        viewModel = viewModel,
                                        onNavigate = { currentScreen = it },
                                        onOpenProfile = { showProfileScreen = true }
                                    )
                                }
                                "chat" -> tabStateHolder.SaveableStateProvider("chat") {
                                    ConversationsScreen(
                                        viewModel = viewModel,
                                        onNavigate = { currentScreen = it },
                                        onOpenChatDetail = { friend ->
                                            activeChatFriend = friend
                                            currentScreen = "chat_detail"
                                        },
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
}
