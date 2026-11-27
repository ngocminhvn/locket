package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.model.Conversation
import com.locket.app.model.Friend
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.LocketBottomBar
import com.locket.app.ui.theme.*

@Composable
fun ConversationsScreen(
    viewModel: LocketViewModel,
    onNavigate: (String) -> Unit,
    onOpenChatDetail: (Friend) -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = LocalContext.current
    val liveConversations by viewModel.conversations.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    var showNewChatDialog by remember { mutableStateOf(false) }

    // Quay lai man danh sach: tai lai conversations that (REST, tuong duong
    // polling cua onSnapshot goc) de preview/so chua doc khong bi cu.
    LaunchedEffect(Unit) {
        viewModel.refreshConversations()
    }

    // Conversations strictly from live server data or friends list (No demo data)
    val conversations = remember(liveConversations, friends) {
        if (liveConversations.isNotEmpty()) {
            liveConversations
        } else {
            friends.map { friend ->
                Conversation(
                    id = friend.uid,
                    friend = friend,
                    // Chua co cuoc tro chuyen that: preview de trong that,
                    // khong bia chuoi "Bat dau cuoc tro chuyen" lam tin cuoi.
                    lastMessage = "",
                    timeAgo = ""
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header: "Trò chuyện" + Avatar (Matches 03-tro-chuyen.jpg)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(40.dp))

                Text(
                    text = "Trò chuyện",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E))
                        .clickable(onClick = onOpenProfile),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentUser.profilePictureUrl.isNotBlank()) {
                        AsyncImage(
                            model = currentUser.profilePictureUrl,
                            contentDescription = "Profile",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Profile",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Conversation List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(conversations) { conv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenChatDetail(conv.friend)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2A2A2C)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (conv.friend.avatarUrl.isNotBlank()) {
                                AsyncImage(
                                    model = conv.friend.avatarUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                // Ten that; chua resolve duoc thi hien username, roi UID that —
                                // khong de trong (bug anh chup man hinh: hang khong ten).
                                text = conv.friend.displayName.ifBlank { conv.friend.username.ifBlank { conv.friend.uid } },
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = conv.lastMessage,
                                color = LocketTextDim,
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = conv.timeAgo,
                                color = LocketTextDim,
                                fontSize = 13.sp
                            )
                            // Chua doc that (is_read == false tren server): cham vang.
                            if (conv.unreadCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(LocketYellow, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Yellow Pen Button (Matches 03-tro-chuyen.jpg)
        FloatingActionButton(
            onClick = { showNewChatDialog = true },
            containerColor = LocketYellow,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 80.dp)
                .size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Tin nhắn mới",
                tint = Color.Black,
                modifier = Modifier.size(24.dp)
            )
        }

        // Bottom Navigation Bar
        Box(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LocketBottomBar(
                currentScreen = "chat",
                onNavigate = onNavigate
            )
        }

        // New Chat Dialog
        if (showNewChatDialog) {
            NewChatDialog(
                friends = friends,
                onSendMessage = { receiverUid, msg ->
                    // Chi bao "da gui" khi server tra 200; loi thi bao loi that.
                    viewModel.sendChatMessage(
                        receiverUid = receiverUid,
                        message = msg,
                        onSuccess = {
                            Toast.makeText(context, "Đã gửi tin nhắn!", Toast.LENGTH_SHORT).show()
                            showNewChatDialog = false
                        },
                        onError = { err ->
                            Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                        }
                    )
                },
                onDismiss = { showNewChatDialog = false }
            )
        }
    }
}

@Composable
private fun NewChatDialog(
    friends: List<Friend>,
    onSendMessage: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFriend by remember { mutableStateOf<Friend?>(friends.firstOrNull()) }
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E22),
        title = { Text("Gửi tin nhắn mới", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Chọn bạn bè:", color = LocketTextDim)
                if (friends.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (f in friends.take(3)) {
                            FilterChip(
                                selected = selectedFriend?.uid == f.uid,
                                onClick = { selectedFriend = f },
                                label = { Text(f.displayName) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    placeholder = { Text("Nhập nội dung tin nhắn...") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val uid = selectedFriend?.uid ?: return@Button
                    if (message.isNotBlank()) {
                        onSendMessage(uid, message)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LocketYellow)
            ) {
                Text("Gửi", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy", color = Color.White)
            }
        }
    )
}
