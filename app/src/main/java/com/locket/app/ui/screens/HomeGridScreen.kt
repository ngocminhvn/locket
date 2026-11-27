package com.locket.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.theme.LocketYellow
import com.locket.app.ui.theme.LocketTextDim
import com.locket.app.ui.components.LocketTopBar
import com.locket.app.ui.theme.LocketBlack

@Composable
fun HomeGridScreen(
    viewModel: LocketViewModel,
    onMomentClick: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val moments by viewModel.filteredMoments.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val selectedFriend by viewModel.selectedFriend.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    var showFriendFilter by remember { androidx.compose.runtime.mutableStateOf(false) }

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
            // Top Bar (Matches 09-home-grid.jpg)
            LocketTopBar(
                title = topBarTitle,
                showDropdownArrow = true,
                avatarUrl = currentUser.profilePictureUrl,
                onTitleClick = { showFriendFilter = true },
                onAvatarClick = onOpenProfile
            )

            // 3-Column Moments Grid with Live Moments
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(moments) { index, moment ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1.0f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF202022))
                            .clickable { onMomentClick(index) }
                    ) {
                        AsyncImage(
                            model = moment.thumbnailUrl,
                            contentDescription = moment.caption,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            // Bottom Navigation
            // Grid only: the original uses three controls, not the five-button moment bar.
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp, top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    Modifier.clip(CircleShape).background(Color(0xFF202022))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onNavigate("history") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Apps, "Kỷ niệm", tint = LocketTextDim, modifier = Modifier.size(25.dp))
                    }
                    Box(
                        Modifier.size(38.dp).border(3.dp, LocketYellow, CircleShape)
                            .clickable { onNavigate("camera") },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(28.dp).background(Color.White, CircleShape))
                    }
                    IconButton(onClick = { onNavigate("chat") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ChatBubble, "Trò chuyện", tint = LocketTextDim, modifier = Modifier.size(25.dp))
                    }
                }
            }
        }

        // Friend Filter Bottom Sheet
        if (showFriendFilter) {
            com.locket.app.ui.components.FriendFilterSheet(
                friends = friends,
                selectedFriend = selectedFriend,
                onSelectFriend = { viewModel.selectFriend(it) },
                onDismiss = { showFriendFilter = false }
            )
        }
    }
}
