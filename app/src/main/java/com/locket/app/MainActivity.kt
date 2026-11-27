package com.locket.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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

                LaunchedEffect(isLoggedIn) {
                    if (!isLoggedIn) {
                        currentScreen = "welcome"
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
                                "camera" -> {
                                    CameraScreen(
                                        viewModel = viewModel,
                                        onNavigate = { currentScreen = it },
                                        onOpenFriends = { showFriendsModal = true },
                                        onOpenProfile = { showProfileScreen = true }
                                    )
                                }
                                "feed" -> {
                                    HomePagerScreen(
                                        viewModel = viewModel,
                                        initialMomentIndex = selectedGridIndex,
                                        onNavigate = { currentScreen = it },
                                        onOpenFriends = { showFriendsModal = true },
                                        onOpenProfile = { showProfileScreen = true }
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
                                "history" -> {
                                    HistoryScreen(
                                        viewModel = viewModel,
                                        onNavigate = { currentScreen = it },
                                        onOpenProfile = { showProfileScreen = true }
                                    )
                                }
                                "chat" -> {
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
