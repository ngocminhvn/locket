package com.locket.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.locket.app.model.Friend
import com.locket.app.model.User
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.liquidGlassEffect
import com.locket.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun FriendsScreen(
    viewModel: LocketViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val friends by viewModel.friends.collectAsState()
    val incomingRequests by viewModel.incomingFriendRequests.collectAsState()
    val recommendations by viewModel.friendRecommendations.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    LaunchedEffect(currentUser.uid) { viewModel.refreshFriendsScreen() }
    var showQr by remember { mutableStateOf(false) }
    if (showQr) QrShareBottomSheet(currentUser, viewModel, onDismiss = { showQr = false })

    var searchQuery by remember { mutableStateOf("") }
    var searchedUser by remember { mutableStateOf<User?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    val searchCache = remember(currentUser.uid) { linkedMapOf<String, Pair<Long, User?>>() }
    LaunchedEffect(searchQuery, currentUser.uid) {
        val query = searchQuery.trim()
        searchedUser = null
        isSearching = false
        if (query.length < 3) return@LaunchedEffect
        val cached = searchCache[query]
        if (cached != null && android.os.SystemClock.elapsedRealtime() - cached.first < 300_000L) {
            searchedUser = cached.second
            return@LaunchedEffect
        }
        isSearching = true
        try {
            kotlinx.coroutines.delay(350)
            val result = viewModel.apiClient.searchUserByUsername(query)
            if (searchQuery.trim() == query) result.onSuccess { user ->
                searchedUser = user
                searchCache[query] = android.os.SystemClock.elapsedRealtime() to user
                while (searchCache.size > 50) searchCache.remove(searchCache.keys.first())
            }
        } finally { if (searchQuery.trim() == query) isSearching = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag Handle Bar (Matches 08-ban-be.jpg)
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(Color(0xFF48484A), RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Title & Subtitle
            Text(
                text = "Bạn bè của bạn",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Thêm các bạn thân của bạn",
                color = LocketTextDim,
                fontSize = 15.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar ("Tìm hoặc thêm bạn bè" - Calls real Locket getUserByUsername)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = LocketTextDim
                    )
                },
                trailingIcon = {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LocketYellow)
                    }
                },
                placeholder = { Text("Tìm hoặc thêm bạn bè", color = LocketTextDim) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0x33FFFFFF),
                    unfocusedBorderColor = Color(0x22FFFFFF),
                    focusedContainerColor = Color(0xFF1C1C1E),
                    unfocusedContainerColor = Color(0xFF1C1C1E)
                ),
                singleLine = true
            )

            // Searched User Result Card
            searchedUser?.let { sUser ->
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassEffect(RoundedCornerShape(16.dp), backgroundColor = Color(0xFF2C2C2E))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Gray),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, null, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(sUser.displayName, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("@${sUser.username}", color = LocketTextDim, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val res = viewModel.apiClient.sendFriendRequest(sUser.uid)
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Đã gửi lời mời kết bạn!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Lỗi kết bạn: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LocketYellow),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Kết bạn", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Subtitle: "Tìm bạn bè từ các ứng dụng khác"
            Text(
                text = "Tìm bạn bè từ các ứng dụng khác",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Social Buttons Row: QR Code, Messenger, Zalo, Insta, Khác
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                SocialShareButton(
                    label = "QR Code",
                    backgroundColor = LocketYellow,
                    icon = { Icon(Icons.Default.QrCode, null, tint = Color.Black) },
                    onClick = { showQr = true }
                )
                SocialShareButton(
                    label = "Messenger",
                    backgroundColor = Color(0xFF0078FF),
                    icon = { Icon(Icons.Default.Chat, null, tint = Color.White) },
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, "Kết bạn với tôi trên Locket! https://locket.cam/${currentUser.username}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Chia sẻ"))
                    }
                )
                SocialShareButton(
                    label = "Zalo",
                    backgroundColor = Color.White,
                    icon = { Text("Zalo", color = Color(0xFF0068FF), fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, "Kết bạn với tôi trên Locket! https://locket.cam/${currentUser.username}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Chia sẻ Zalo"))
                    }
                )
                SocialShareButton(
                    label = "Insta",
                    backgroundColor = Color(0xFFE1306C),
                    icon = { Icon(Icons.Default.CameraAlt, null, tint = Color.White) },
                    onClick = { Toast.makeText(context, "Instagram", Toast.LENGTH_SHORT).show() }
                )
                SocialShareButton(
                    label = "Khác",
                    backgroundColor = Color(0xFF2C2C2E),
                    icon = { Icon(Icons.Default.Share, null, tint = Color.White) },
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, "Kết bạn với tôi trên Locket! https://locket.cam/${currentUser.username}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Chia sẻ link"))
                    }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // "Tip! Add by username" Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassEffect(RoundedCornerShape(20.dp), backgroundColor = Color(0xFF1E1E20))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tip! Add by username",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Friends can search @${currentUser.username}",
                        color = LocketTextDim,
                        fontSize = 13.sp
                    )
                }
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = LocketTextDim,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Loi moi ket ban den (inbox that: users/{uid}/incoming_friend_requests).
            // Khong co loi moi thi khong hien section.
            if (incomingRequests.isNotEmpty()) {
                Text(
                    text = "Lời mời kết bạn (${incomingRequests.size})",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    incomingRequests.forEach { request ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2C2C2E))
                                    .border(2.dp, LocketYellow, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (request.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = request.avatarUrl,
                                        contentDescription = null,
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

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = request.displayName.ifBlank { request.username.ifBlank { request.uid } },
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (request.username.isNotBlank() && request.username != request.displayName) {
                                    Text(
                                        text = "@${request.username}",
                                        color = LocketTextDim,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    viewModel.acceptFriendRequest(request.uid,
                                        onDone = {
                                            Toast.makeText(context, "Đã kết bạn với ${request.displayName.ifBlank { request.uid }}", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, "Lỗi chấp nhận: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = LocketYellow),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Chấp nhận", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    viewModel.declineFriendRequest(request.uid,
                                        onDone = {
                                            Toast.makeText(context, "Đã từ chối lời mời kết bạn", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, "Lỗi từ chối: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Từ chối", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Goi y ket ban (Batch 6, defensive): chi hien khi server tra 200
            // (getFriendRecommendations). Loi/401 -> danh sach rong -> an han.
            if (recommendations.isNotEmpty()) {
                Text(
                    text = "Gợi ý kết bạn",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    recommendations.forEach { rec ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2C2C2E))
                                    .border(2.dp, LocketYellow, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (rec.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = rec.avatarUrl,
                                        contentDescription = null,
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

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rec.displayName.ifBlank { rec.username.ifBlank { rec.uid } },
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                val subtitle = buildString {
                                    if (rec.username.isNotBlank()) append("@${rec.username}")
                                    if (rec.mutualFriendCount > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${rec.mutualFriendCount} bạn chung")
                                    }
                                }
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        color = LocketTextDim,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = viewModel.apiClient.sendFriendRequest(rec.uid)
                                        if (res.isSuccess) {
                                            viewModel.dismissRecommendation(rec.uid)
                                            Toast.makeText(context, "Đã gửi lời mời kết bạn!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Lỗi kết bạn: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = LocketYellow),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Kết bạn", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = { viewModel.dismissRecommendation(rec.uid) },
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color(0xFF2C2C2E), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Ẩn gợi ý",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Friends List Title
            Text(
                text = "Bạn bè của bạn (${friends.size})",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Friends List with X button (Calls real removeFriend API)
            val filteredFriends = friends.filter {
                searchQuery.isBlank() || it.displayName.contains(searchQuery, ignoreCase = true) || it.username.contains(searchQuery, ignoreCase = true)
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(filteredFriends) { friend ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(Color(0xFF2C2C2E))
                                    .border(2.dp, LocketYellow, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (friend.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = friend.avatarUrl,
                                        contentDescription = null,
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
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(LocketYellow, CircleShape)
                                    .border(1.5.dp, Color.Black, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("💛", fontSize = 8.sp)
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = friend.displayName.ifBlank { friend.username.ifBlank { friend.uid } },
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            if (friend.username.isNotBlank() && friend.username != friend.displayName) {
                                Text(
                                    text = "@${friend.username}",
                                    color = LocketTextDim,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Remove friend button ('X') calling removeFriend callable
                        IconButton(
                            onClick = {
                                viewModel.removeFriend(friend.uid) {
                                    Toast.makeText(context, "Đã xóa ${friend.displayName}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color(0xFF2C2C2E), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Xóa",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242426)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Đóng", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SocialShareButton(
    label: String,
    backgroundColor: Color,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp
        )
    }
}
