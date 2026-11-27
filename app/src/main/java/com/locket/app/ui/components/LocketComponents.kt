package com.locket.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.ui.theme.*

@Composable
fun ShutterButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "shutterScale"
    )

    Box(
        modifier = modifier
            .size(86.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer Yellow Ring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(width = 4.5.dp, color = LocketYellow, shape = CircleShape)
        )
        // Inner White Shutter
        Box(
            modifier = Modifier
                .size(70.dp)
                .background(color = Color.White, shape = CircleShape)
        )
    }
}

@Composable
fun LocketTopBar(
    title: String = "Bạn bè",
    showDropdownArrow: Boolean = false,
    avatarUrl: String = "",
    onSpeakerClick: () -> Unit = {},
    onTitleClick: () -> Unit = {},
    onAvatarClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Speaker / Horn Button
        Box(
            modifier = Modifier
                .size(46.dp)
                .liquidGlassEffect(CircleShape)
                .clickable(onClick = onSpeakerClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Campaign,
                contentDescription = "Thông báo",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // Center Pill (Friends count or filter)
        Row(
            modifier = Modifier
                .liquidGlassEffect(RoundedCornerShape(24.dp))
                .clickable(onClick = onTitleClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!showDropdownArrow) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            if (showDropdownArrow) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Avatar with yellow heart badge
        Box(
            modifier = Modifier
                .size(46.dp)
                .clickable(onClick = onAvatarClick),
            contentAlignment = Alignment.BottomEnd
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xFF2C2C2E))
            ) {
                if (avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = "Avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

        }
    }
}

@Composable
fun LocketBottomBar(
    currentScreen: String,
    onNavigate: (String) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .liquidGlassEffect(RoundedCornerShape(32.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // All three slots have the same fixed dimensions regardless of
            // selection. Only the background and icon tint change.
            listOf(
                Triple("history", Icons.Default.Apps, "Kỷ niệm"),
                Triple("camera", Icons.Default.Home, "Camera"),
                Triple("chat", Icons.Default.ChatBubble, "Trò chuyện")
            ).forEach { (screen, icon, label) ->
                val active = currentScreen == screen
                Box(
                    Modifier.size(width = 64.dp, height = 46.dp)
                        .clip(CircleShape)
                        .background(if (active) Color(0x33FFFFFF) else Color.Transparent)
                        .clickable { onNavigate(screen) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = label,
                        tint = if (active) Color.White else LocketTextDim,
                        modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
fun FeedBottomBar(
    onGridClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onShutterClick: () -> Unit,
    onChatClick: () -> Unit,
    onShareClick: () -> Unit,
    separated: Boolean = false
) {
    if (separated) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onGridClick, modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF202022))) {
                Icon(Icons.Outlined.GridView, "Lưới", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Row(
                Modifier.clip(CircleShape).background(Color(0xFF202022)).padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onHistoryClick, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Apps, "Kỷ niệm", tint = LocketTextDim, modifier = Modifier.size(25.dp))
                }
                Box(Modifier.size(38.dp).border(3.dp, LocketYellow, CircleShape).clickable(onClick = onShutterClick),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(28.dp).background(Color.White, CircleShape))
                }
                IconButton(onClick = onChatClick, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ChatBubble, "Trò chuyện", tint = LocketTextDim, modifier = Modifier.size(25.dp))
                }
            }
            IconButton(onClick = onShareClick, modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF202022))) {
                Icon(Icons.Default.Share, "Chia sẻ", tint = Color.White, modifier = Modifier.size(25.dp))
            }
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .liquidGlassEffect(RoundedCornerShape(32.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // 4-squares grid icon
            IconButton(onClick = onGridClick, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = "Lưới",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            // 9-dots calendar icon
            IconButton(onClick = onHistoryClick, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = "Kỷ niệm",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            // Center yellow shutter circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(3.dp, LocketYellow, CircleShape)
                    .clickable(onClick = onShutterClick),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.White, CircleShape)
                )
            }
            // Chat bubble
            IconButton(onClick = onChatClick, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.ChatBubble,
                    contentDescription = "Trò chuyện",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            // Share icon
            IconButton(onClick = onShareClick, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Chia sẻ",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendFilterSheet(
    friends: List<com.locket.app.model.Friend>,
    selectedFriend: com.locket.app.model.Friend?,
    onSelectFriend: (com.locket.app.model.Friend?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E20),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(Color(0xFF48484A), RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // "Tất cả bạn bè" Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        onSelectFriend(null)
                        onDismiss()
                    }
                    .padding(vertical = 12.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (selectedFriend == null) LocketYellow else Color(0xFF2C2C2E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        tint = if (selectedFriend == null) Color.Black else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = "Tất cả bạn bè",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (selectedFriend == null) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = LocketYellow,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            HorizontalDivider(
                color = Color(0x22FFFFFF),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Friends List
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(friends.size) { index ->
                    val friend = friends[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                onSelectFriend(friend)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2C2C2E)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (friend.avatarUrl.isNotBlank()) {
                                AsyncImage(
                                    model = friend.avatarUrl,
                                    contentDescription = friend.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = friend.displayName,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (friend.username.isNotBlank() && friend.username != friend.displayName) {
                                Text(
                                    text = "@${friend.username}",
                                    color = LocketTextDim,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        if (selectedFriend?.uid == friend.uid) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = LocketYellow,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = LocketTextDim,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
