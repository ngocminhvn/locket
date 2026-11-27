package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.locket.app.model.ChatMessage
import com.locket.app.model.Friend
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.liquidGlassEffect
import com.locket.app.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat that 2 chieu: tin nhan doc tu Firestore conversations/{id}/messages
 * (Room cache-first, sync REST — tuong duong polling cua onSnapshot goc),
 * gui qua sendChatMessageV2, danh dau da doc qua markAsRead.
 * Khong dung tin nhan dung tu moment, khong them tin local khi gui.
 * Cuon len dau danh sach thi tai them tin cu (nextPageToken); khi dang mo
 * chat thi polling nhe moi ~8s de tin moi ve gan realtime hon — chua phai
 * realtime that (Firestore :listen streaming chua lam).
 * Nhan giu 1 tin: reaction (bam dung emoji da react thi go), thu hoi tin
 * cua minh (deleteChatMessage — goc goi la "unsend"), bao cao tin nguoi
 * khac (reportMessage).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatDetailScreen(
    viewModel: LocketViewModel,
    friend: Friend,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var inputMessage by remember { mutableStateOf("") }
    var reactingTo by remember { mutableStateOf<ChatMessage?>(null) }
    // Buoc xac nhan trong menu tin nhan: null = chon emoji/menu,
    // "delete" = xac nhan thu hoi, "report" = xac nhan bao cao.
    var menuConfirm by remember { mutableStateOf<String?>(null) }
    // Da cuon xuong duoi 1 lan sau khi mo chat chua (chan tu tai tin cu
    // ngay khi vua mo).
    var chatSettled by remember(friend.uid) { mutableStateOf(false) }

    val messages by viewModel.chatMessages.collectAsState()
    val isChatLoading by viewModel.isChatLoading.collectAsState()
    val isLoadingOlderChat by viewModel.isLoadingOlderChat.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val myUid = currentUser.uid
    val listState = rememberLazyListState()

    val displayName = friend.displayName.ifBlank { friend.username.ifBlank { friend.uid } }

    // Mo man chat: tim cuoc tro chuyen that theo uid ban, tai tin that.
    LaunchedEffect(friend.uid) {
        viewModel.openChat(friend.uid)
    }

    // Tin moi ve thi cuon xuong duoi — chi khi tin moi nhat doi (key la id
    // tin cuoi): tai them tin cu khong doi tin moi nhat nen khong keo xuong.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
            chatSettled = true
        }
    }

    // Polling nhe khi dang mo chat: refresh moi ~8s de tin moi ve gan
    // realtime hon. Dung khi roi man vi LaunchedEffect bi huy.
    LaunchedEffect(activeConversationId) {
        val convId = activeConversationId ?: return@LaunchedEffect
        while (true) {
            delay(8000)
            viewModel.refreshChat(convId)
        }
    }

    // Cuon toi dau danh sach (tin cu nhat) thi tai them tin cu.
    // LazyColumn khong reverseLayout: dau danh sach = index 0 = tin cu nhat.
    LaunchedEffect(activeConversationId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                val convId = viewModel.activeConversationId.value ?: return@collect
                if (!chatSettled || idx != 0) return@collect
                if (messages.isEmpty() || !viewModel.chatHasMoreOlder.value || viewModel.isLoadingOlderChat.value) return@collect
                val info = listState.layoutInfo
                if (info.visibleItemsInfo.isEmpty() || info.totalItemsCount <= info.visibleItemsInfo.size) return@collect
                val anchorOffset = listState.firstVisibleItemScrollOffset
                viewModel.loadOlderChatMessages(convId) { added ->
                    // Tin cu chen len dau day tin dang xem xuong `added` vi
                    // tri — cuon lai dung cho do de giu vi tri doc.
                    if (added > 0) listState.scrollToItem(added, anchorOffset)
                }
            }
    }

    fun formatTime(createdAtMs: Long): String {
        if (createdAtMs <= 0L) return ""
        return SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(createdAtMs))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Bar: Back arrow, Avatar with yellow border, Friend name
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E)),
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

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = displayName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Message Stream: tin nhan that tu server (cache Room + sync)
            if (isChatLoading && messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = LocketYellow)
                }
            } else if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Chưa có tin nhắn nào",
                        color = LocketTextDim,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Dang tai them tin cu: vong loading nho o dau danh sach
                    // (chi them item nay khi dang tai, khong doi index tin).
                    if (isLoadingOlderChat) {
                        item(key = "loading-older") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = LocketYellow,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                    items(messages, key = { it.id }) { msg ->
                        val timeStr = formatTime(msg.createdAtMs)
                        if (msg.thumbnailUrl != null) {
                            // Shared Moment Card in Chat (tin kem moment that)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (timeStr.isNotBlank()) {
                                    Text(
                                        text = timeStr,
                                        color = LocketTextDim,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1.0f)
                                        .clip(RoundedCornerShape(32.dp))
                                        .background(Color(0xFF1E1E1E))
                                        .combinedClickable(
                                            onClick = {},
                                            onLongClick = { reactingTo = msg }
                                        )
                                ) {
                                    AsyncImage(
                                        model = msg.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )

                                    // Overlay tag: ten that + thoi gian that cua moment
                                    Row(
                                        modifier = Modifier
                                            .padding(14.dp)
                                            .liquidGlassEffect(RoundedCornerShape(16.dp), backgroundColor = Color(0x99000000))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                            .align(Alignment.TopStart),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(Color.Gray)
                                        )
                                        Text(
                                            text = if (msg.isMe) "Bạn • $timeStr" else "$displayName • $timeStr",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                // Text kem theo moment (neu co)
                                if (msg.text.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    MessageBubble(msg = msg, onLongClick = { reactingTo = msg })
                                }
                                ReactionRow(reactions = msg.reactions)
                            }
                        } else if (msg.isMe) {
                            // Sent message bubble: White rounded pill on the right
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color.White)
                                        .combinedClickable(
                                            onClick = {},
                                            onLongClick = { reactingTo = msg }
                                        )
                                        .padding(horizontal = 18.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        color = Color.Black,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                ReactionRow(reactions = msg.reactions)
                            }
                        } else {
                            // Received message bubble: Avatar on left + dark gray pill
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF2C2C2E)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Color(0xFF2C2C2E))
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = { reactingTo = msg }
                                            )
                                            .padding(horizontal = 18.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = msg.text,
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                ReactionRow(reactions = msg.reactions)
                            }
                        }
                    }
                }
            }

            // Bottom Message Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .liquidGlassEffect(RoundedCornerShape(28.dp), backgroundColor = Color(0xD9222224))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputMessage,
                    onValueChange = { inputMessage = it },
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

                if (inputMessage.isNotBlank()) {
                    Text(
                        text = "Gửi",
                        color = LocketYellow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                val text = inputMessage
                                viewModel.sendChatMessage(
                                    receiverUid = friend.uid,
                                    message = text,
                                    onSuccess = { inputMessage = "" },
                                    onError = { err ->
                                        Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                            .padding(8.dp)
                    )
                }
            }
        }
    }

    // Menu tin nhan (nhan giu 1 tin nhan that):
    // - Chon emoji: gui reaction that qua sendChatMessageReaction; bam dung
    //   emoji minh da react thi go (removeChatMessageReaction).
    // - "Xoa tin nhan": chi tin do minh gui (goc goi la "unsend") -> hoi
    //   xac nhan roi goi deleteChatMessage {message_uid, conversation_uid}.
    // - "Bao cao tin nhan": chi tin cua nguoi khac -> hoi xac nhan roi goi
    //   reportMessage {message_uid, conversation_uid}.
    // Chi toast khi server tra 200.
    val target = reactingTo
    if (target != null) {
        val myReaction = target.reactions.firstOrNull { it.senderUid == myUid }
        AlertDialog(
            onDismissRequest = { reactingTo = null; menuConfirm = null },
            containerColor = Color(0xFF1E1E22),
            title = {
                Text(
                    text = when (menuConfirm) {
                        "delete" -> "Thu hồi tin nhắn?"
                        "report" -> "Báo cáo tin nhắn?"
                        else -> "Chọn biểu cảm"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                when (menuConfirm) {
                    "delete" -> Text(
                        text = "Tin nhắn này sẽ bị thu hồi.",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    "report" -> Text(
                        text = "Tin nhắn này sẽ được gửi báo cáo.",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    else -> Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            for (emoji in listOf("🔥", "😍", "💛", "😂", "🥺")) {
                                Text(
                                    text = emoji,
                                    fontSize = 28.sp,
                                    modifier = Modifier
                                        .clickable {
                                            val convId = activeConversationId
                                            if (convId.isNullOrBlank()) {
                                                Toast.makeText(context, "Chưa mở được cuộc trò chuyện", Toast.LENGTH_SHORT).show()
                                                reactingTo = null
                                            } else {
                                                viewModel.reactToChatMessage(
                                                    conversationId = convId,
                                                    messageId = target.id,
                                                    emoji = emoji,
                                                    onSuccess = {
                                                        Toast.makeText(context, "Đã gửi $emoji", Toast.LENGTH_SHORT).show()
                                                        reactingTo = null
                                                    },
                                                    onRemoved = {
                                                        Toast.makeText(context, "Đã gỡ $emoji", Toast.LENGTH_SHORT).show()
                                                        reactingTo = null
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                                        reactingTo = null
                                                    }
                                                )
                                            }
                                        }
                                        .padding(6.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (target.isMe) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { menuConfirm = "delete" }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text("Xóa tin nhắn", color = Color(0xFFFF6B6B), fontSize = 16.sp)
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { menuConfirm = "report" }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text("Báo cáo tin nhắn", color = Color(0xFFFF6B6B), fontSize = 16.sp)
                            }
                        }
                        if (myReaction != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val convId = activeConversationId
                                        if (convId.isNullOrBlank()) {
                                            Toast.makeText(context, "Chưa mở được cuộc trò chuyện", Toast.LENGTH_SHORT).show()
                                            reactingTo = null
                                        } else {
                                            viewModel.removeChatMessageReaction(
                                                conversationId = convId,
                                                messageId = target.id,
                                                emoji = myReaction.emoji,
                                                onSuccess = {
                                                    Toast.makeText(context, "Đã gỡ ${myReaction.emoji}", Toast.LENGTH_SHORT).show()
                                                    reactingTo = null
                                                },
                                                onError = { err ->
                                                    Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                                    reactingTo = null
                                                }
                                            )
                                        }
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text("Gỡ reaction ${myReaction.emoji}", color = Color.White, fontSize = 16.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (menuConfirm) {
                    "delete" -> TextButton(onClick = {
                        val convId = activeConversationId
                        if (convId.isNullOrBlank()) {
                            Toast.makeText(context, "Chưa mở được cuộc trò chuyện", Toast.LENGTH_SHORT).show()
                            reactingTo = null
                            menuConfirm = null
                        } else {
                            viewModel.deleteChatMessage(
                                messageId = target.id,
                                conversationId = convId,
                                onSuccess = {
                                    Toast.makeText(context, "Đã thu hồi tin nhắn", Toast.LENGTH_SHORT).show()
                                    reactingTo = null
                                    menuConfirm = null
                                },
                                onError = { err ->
                                    Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                    reactingTo = null
                                    menuConfirm = null
                                }
                            )
                        }
                    }) {
                        Text("Thu hồi", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                    }
                    "report" -> TextButton(onClick = {
                        val convId = activeConversationId
                        if (convId.isNullOrBlank()) {
                            Toast.makeText(context, "Chưa mở được cuộc trò chuyện", Toast.LENGTH_SHORT).show()
                            reactingTo = null
                            menuConfirm = null
                        } else {
                            viewModel.reportChatMessage(
                                messageId = target.id,
                                conversationId = convId,
                                onSuccess = {
                                    Toast.makeText(context, "Đã gửi báo cáo", Toast.LENGTH_SHORT).show()
                                    reactingTo = null
                                    menuConfirm = null
                                },
                                onError = { err ->
                                    Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                    reactingTo = null
                                    menuConfirm = null
                                }
                            )
                        }
                    }) {
                        Text("Báo cáo", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                    }
                    else -> {}
                }
            },
            dismissButton = {
                TextButton(onClick = { reactingTo = null; menuConfirm = null }) {
                    Text("Hủy", color = Color.White)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: ChatMessage, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (msg.isMe) Color.White else Color(0xFF2C2C2E))
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(
                text = msg.text,
                color = if (msg.isMe) Color.Black else Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ReactionRow(reactions: List<com.locket.app.model.MessageReaction>) {
    if (reactions.isEmpty()) return
    Row(
        modifier = Modifier.padding(top = 4.dp, start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (r in reactions) {
            Text(text = r.emoji, fontSize = 16.sp)
        }
    }
}
