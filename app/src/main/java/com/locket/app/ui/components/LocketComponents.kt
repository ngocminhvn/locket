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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.ui.theme.*
import com.wexpa.liquidglass.Glass
import com.wexpa.liquidglass.glassEffect
import com.locket.app.ui.glass.LocketGlassTabBar

@Composable
fun ShutterButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = androidx.compose.animation.core.tween(120),
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
    onAvatarClick: () -> Unit = {},
    dropdownExpanded: Boolean = false,
    adaptiveBackground: Boolean = false,
    showAvatar: Boolean = true
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
                .then(if (adaptiveBackground) Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                    else Modifier.liquidGlassEffect(CircleShape))
                .clickable(onClick = onSpeakerClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (adaptiveBackground) Icons.Filled.Campaign else Icons.Outlined.Campaign,
                contentDescription = "Thông báo",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // Equal side slots keep the center title stable, including long names.
        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .then(if (adaptiveBackground) Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                    else Modifier.liquidGlassEffect(RoundedCornerShape(24.dp)))
                .clickable(onClick = onTitleClick)
                .padding(horizontal = if (adaptiveBackground) 20.dp else 18.dp, vertical = if (adaptiveBackground) 12.dp else 10.dp),
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
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (showDropdownArrow) {
                Icon(
                    imageVector = if (dropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        }
        if (showAvatar) LocketProfileAvatar(avatarUrl, onAvatarClick)
        else Spacer(Modifier.size(46.dp))
    }
}

@Composable
fun LocketProfileAvatar(avatarUrl: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(46.dp).clip(CircleShape).background(LocketAvatarBg)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(avatarUrl, "Hồ sơ", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.Person, "Hồ sơ", tint = Color.White, modifier = Modifier.size(24.dp))
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
    separated: Boolean = false,
    currentScreen: String = "camera",
    showSideActions: Boolean = true,
    unreadConversations: Int = 0,
    feedMode: Boolean = showSideActions
) {
    if (separated) {
        // Use the unmodified Calm optical material from TKB SV. Its native
        // tint is translucent; do not cover the backdrop with LocketPillBg.
        // Match the central bar's translucent color response without adding
        // its refractive rim or motion to the two auxiliary buttons.
        val sideGlass = remember {
            Glass(
                style = requireNotNull(Glass.TabBar.style(dark = true)).copy(
                    refractionDepth = 0.dp,
                    dispersion = 0f,
                    capFold = 0f,
                    mirror = 0f,
                    specular = 0f,
                    counterLight = 0f,
                    edgeShadow = 0f
                ),
                light = Glass.TabBar.light(dark = true)
            )
        }
        val inactive = Color.White.copy(alpha = 0.52f)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showSideActions) {
                IconButton(onClick = onGridClick, modifier = Modifier.size(54.dp).glassEffect(sideGlass, CircleShape)) {
                    Icon(Icons.Outlined.GridView, "Lưới", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            } else Spacer(Modifier.size(54.dp))
            // Same Calm selector/held lens and release-to-select gesture as TKB SV.
            // The upstream bar owns touch dispatch; its items must not add IconButtons.
            val selected = when (currentScreen) { "history" -> 0; "chat" -> 2; else -> 1 }
            LocketGlassTabBar(
                    itemCount = 3,
                    selectedIndex = selected,
                    onSelected = { index ->
                        when (index) {
                            0 -> onHistoryClick()
                            1 -> onShutterClick()
                            2 -> onChatClick()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { index, isSelected ->
                    val tint = if (isSelected) Color.White else inactive
                    when (index) {
                        0 -> Icon(Icons.Default.Apps, "Kỷ niệm", tint = tint, modifier = Modifier.size(23.dp))
                        1 -> if (feedMode) {
                            Box(Modifier.size(38.dp).border(3.dp, LocketYellow, CircleShape), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(29.dp).background(Color.White, CircleShape))
                            }
                        } else {
                            Icon(Icons.Default.Home, "Camera", tint = tint, modifier = Modifier.size(24.dp))
                        }
                        2 -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ChatBubble,
                                if (unreadConversations > 0) "Trò chuyện, $unreadConversations cuộc trò chuyện chưa đọc" else "Trò chuyện",
                                tint = tint, modifier = Modifier.size(24.dp))
                            UnreadConversationBadge(unreadConversations, Modifier.align(Alignment.TopEnd))
                        }
                    }
                }
            if (showSideActions) {
                IconButton(onClick = onShareClick, modifier = Modifier.size(54.dp).glassEffect(sideGlass, CircleShape)) {
                    Icon(Icons.Default.Share, "Chia sẻ", tint = Color.White, modifier = Modifier.size(25.dp))
                }
            } else Spacer(Modifier.size(54.dp))
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
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onChatClick, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.ChatBubble,
                        contentDescription = if (unreadConversations > 0) "Trò chuyện, $unreadConversations cuộc trò chuyện chưa đọc" else "Trò chuyện",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                UnreadConversationBadge(unreadConversations, Modifier.align(Alignment.TopEnd))
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
        containerColor = LocketSheetBg,
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
                        .background(if (selectedFriend == null) LocketYellow else LocketAvatarBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        tint = if (selectedFriend == null) LocketOnYellow else Color.White,
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
                                .background(LocketAvatarBg),
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

/** Display-only badge. The count is derived from the cached conversation list. */
@Composable
private fun UnreadConversationBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.height(17.dp).widthIn(min = 17.dp)
        .clip(CircleShape).background(LocketYellow).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else count.toString(), color = LocketOnYellow,
            fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(lineHeight = 12.sp))
    }
}
