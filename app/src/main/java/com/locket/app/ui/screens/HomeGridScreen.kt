package com.locket.app.ui.screens

import com.locket.app.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.LocketTopBar
import com.locket.app.ui.components.liquidGlassEffect

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
    val historyLoading by viewModel.historyLoading.collectAsState()
    val historyHasMore by viewModel.historyHasMore.collectAsState()
    val historyError by viewModel.historyError.collectAsState()
    var showFriendFilter by remember { androidx.compose.runtime.mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val cellPx = ((androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels - 12 * androidx.compose.ui.platform.LocalDensity.current.density) / 3).toInt().coerceAtLeast(1)
    LaunchedEffect(gridState, moments, cellPx) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            viewModel.prefetchThumbnails(moments.drop((last + 1).coerceAtLeast(0)).take(9).map { it.thumbnailUrl }, cellPx)
        }
    }

    // Tai them lich su khi cuon gan het luoi (con ~9 anh = 3 hang cuoi).
    // Chi goi khi chua het, khong dang tai, khong loi; ViewModel tu chan
    // goi trung (loadOlderMoments return ngay khi dang tai).
    val nearEnd by remember(moments.size) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            moments.isNotEmpty() && lastVisible >= (moments.size - 9).coerceAtLeast(0)
        }
    }
    LaunchedEffect(nearEnd, historyLoading, historyHasMore, historyError) {
        if (nearEnd && historyHasMore && !historyLoading && historyError == null) {
            viewModel.loadOlderMoments()
        }
    }

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
                state = gridState,
                contentPadding = PaddingValues(
                    start = 2.dp, end = 2.dp, top = 26.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 76.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(moments) { index, moment ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1.0f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(LocketJournalDayBg)
                            .clickable { viewModel.prefetchThumbnails(listOf(moment.thumbnailUrl)); onMomentClick(index) }
                    ) {
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(moment.thumbnailUrl).size(cellPx).build(),
                            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(LocketSheetSurface),
                            contentDescription = moment.caption,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                // Item cuoi luoi: dang tai thi xoay vong, loi thi nut thu lai.
                // Het lich su (hasMore == false) thi khong hien gi them.
                if (historyLoading || historyError != null) {
                    // 'item' khong co trong foundation 1.7 (BOM 2024.09.02) nen
                    // dung items(count = 1) thay the.
                    items(1, span = { GridItemSpan(3) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (historyLoading) {
                                CircularProgressIndicator(
                                    color = LocketYellow,
                                    modifier = Modifier.size(28.dp)
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Không tải được ảnh cũ",
                                        color = LocketTextDim,
                                        fontSize = 14.sp
                                    )
                                    TextButton(onClick = { viewModel.loadOlderMoments() }) {
                                        Text(
                                            text = "Thử lại",
                                            color = LocketYellow,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }

        // The grid runs behind the floating three-button navigation, rather
        // than reserving an opaque strip at the bottom of the screen.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(
                    Color.Transparent, LocketBlack.copy(alpha = 0.40f), LocketBlack.copy(alpha = 0.88f)
                )))
                .navigationBarsPadding().padding(bottom = 12.dp, top = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                Modifier.liquidGlassEffect(CircleShape, backgroundColor = Color(0xCC242424), borderWidth = 0.dp)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(width = 64.dp, height = 46.dp)
                    .clip(CircleShape).clickable { onNavigate("history") },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Apps, "Kỷ niệm", tint = LocketTextDim, modifier = Modifier.size(24.dp))
                }
                Box(Modifier.size(width = 64.dp, height = 46.dp)
                    .clip(CircleShape).clickable { onNavigate("camera") },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(38.dp).border(3.dp, LocketYellow, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).background(Color.White, CircleShape))
                    }
                }
                Box(Modifier.size(width = 64.dp, height = 46.dp)
                    .clip(CircleShape).clickable { onNavigate("chat") },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ChatBubble, "Trò chuyện", tint = LocketTextDim, modifier = Modifier.size(24.dp))
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
