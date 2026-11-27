package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.model.Moment
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.*
import com.locket.app.ui.theme.*

@Composable
fun HomeMomentScreen(
    viewModel: LocketViewModel,
    initialMomentIndex: Int = 0,
    onNavigate: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = LocalContext.current
    val moments by viewModel.filteredMoments.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val selectedFriend by viewModel.selectedFriend.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    var currentIndex by remember { mutableStateOf(initialMomentIndex.coerceIn(0, maxOf(0, moments.size - 1))) }
    var showFriendFilter by remember { mutableStateOf(false) }

    val currentMoment = if (moments.isNotEmpty()) moments[currentIndex.coerceIn(0, moments.size - 1)] else null
    var messageText by remember { mutableStateOf("") }
    var floatingReaction by remember { mutableStateOf<String?>(null) }

    val topBarTitle = if (selectedFriend == null) {
        "Tất cả bạn bè"
    } else {
        val firstName = selectedFriend!!.displayName.trim().split("\\s+".toRegex()).lastOrNull() ?: selectedFriend!!.displayName
        "$firstName"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            LocketTopBar(
                title = topBarTitle,
                showDropdownArrow = true,
                avatarUrl = currentUser.profilePictureUrl,
                onSpeakerClick = {
                    viewModel.syncAllData()
                    Toast.makeText(context, "Đã làm mới khoảnh khắc từ server", Toast.LENGTH_SHORT).show()
                },
                onTitleClick = { showFriendFilter = true },
                onAvatarClick = onOpenProfile
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Main Moment Squircle Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(1.0f)
                    .clip(RoundedCornerShape(36.dp))
                    .background(Color(0xFF1E1E1E))
                    .clickable {
                        if (moments.isNotEmpty()) {
                            currentIndex = (currentIndex + 1) % moments.size
                        }
                    }
            ) {
                if (currentMoment != null) {
                    AsyncImage(
                        model = currentMoment.thumbnailUrl,
                        contentDescription = "Moment",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // Caption overlay inside photo (Matches frame_010.jpg)
                    if (currentMoment.caption.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                                .liquidGlassEffect(RoundedCornerShape(20.dp), backgroundColor = Color(0xB31E1E20))
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = currentMoment.caption,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Chưa có khoảnh khắc nào", color = LocketTextDim)
                    }
                }

                // Floating reaction animation
                floatingReaction?.let { emoji ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .animateFloatingReaction { floatingReaction = null }
                    ) {
                        Text(text = emoji, fontSize = 72.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // User Info under photo: avatar + "Hiếu 💛 8m" + caption
            if (currentMoment != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2C2C2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentMoment.userAvatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = currentMoment.userAvatarUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Text(
                        text = currentMoment.userName,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "💛",
                        fontSize = 14.sp
                    )

                    Text(
                        text = currentMoment.timeAgo,
                        color = LocketTextDim,
                        fontSize = 16.sp
                    )
                }

                if (currentMoment.caption.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = currentMoment.caption,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Reaction Bar (Calls real reactToMoment API & sends real chat message)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .liquidGlassEffect(RoundedCornerShape(28.dp), backgroundColor = Color(0xD9222224))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Text input
                TextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text("Gửi tin nhắn...", color = Color(0xFF8E8E93), fontSize = 15.sp) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )

                // Quick emoji buttons calling reactToMoment
                val emojis = listOf("🔥", "😍", "💛")
                for (emoji in emojis) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                floatingReaction = emoji
                                if (currentMoment != null) {
                                    viewModel.reactToMoment(
                                        momentId = currentMoment.id,
                                        ownerUid = currentMoment.userUid,
                                        reaction = emoji
                                    )
                                }
                                Toast.makeText(context, "Đã gửi $emoji", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 20.sp)
                    }
                }

                // Send message button if text entered
                if (messageText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            if (currentMoment != null) {
                                viewModel.sendChatMessage(currentMoment.userUid, messageText)
                                Toast.makeText(context, "Đã gửi tin nhắn!", Toast.LENGTH_SHORT).show()
                                messageText = ""
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Gửi",
                            tint = LocketYellow,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Chọn biểu cảm", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEmotions,
                            contentDescription = "Emoji",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Feed Bottom Navigation Bar (Matches 04-home-moment.jpg)
            FeedBottomBar(
                onGridClick = { onNavigate("grid") },
                onHistoryClick = { onNavigate("history") },
                onShutterClick = { onNavigate("camera") },
                onChatClick = { onNavigate("chat") },
                onShareClick = { onNavigate("profile") }
            )
        }

        // Friend Filter Bottom Sheet (Matches frame_018.jpg)
        if (showFriendFilter) {
            FriendFilterSheet(
                friends = friends,
                selectedFriend = selectedFriend,
                onSelectFriend = { viewModel.selectFriend(it) },
                onDismiss = { showFriendFilter = false }
            )
        }
    }
}

@Composable
private fun Modifier.animateFloatingReaction(onEnd: () -> Unit): Modifier {
    var animVal by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        animate(0f, 1f, animationSpec = tween(700, easing = FastOutSlowInEasing)) { value, _ ->
            animVal = value
        }
        onEnd()
    }
    return this
        .offset(y = (-100 * animVal).dp)
        .scale(1f + 0.4f * (1f - animVal))
}
