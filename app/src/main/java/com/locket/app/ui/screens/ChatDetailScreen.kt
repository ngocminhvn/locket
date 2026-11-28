package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.model.ChatMessage
import com.locket.app.model.Friend
import com.locket.app.model.MessageReaction
import com.locket.app.ui.LocketViewModel
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
 * chat thi polling nhe moi ~12s de tin moi ve gan realtime hon — chua phai
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputMessage by remember(friend.uid) { mutableStateOf("") }
    var showQuickEmojis by remember { mutableStateOf(false) }
    var sending by remember(friend.uid) { mutableStateOf(false) }
    var reactingTo by remember { mutableStateOf<ChatMessage?>(null) }
    // Buoc xac nhan trong menu tin nhan: null = chon emoji/menu,
    // "delete" = xac nhan thu hoi, "report" = xac nhan bao cao.
    var menuConfirm by remember { mutableStateOf<String?>(null) }
    // Da cuon xuong duoi 1 lan sau khi mo chat chua (chan tu tai tin cu
    // ngay khi vua mo).
    var chatSettled by remember(friend.uid) { mutableStateOf(false) }

    val messages by viewModel.chatMessages.collectAsState()
    val pending by viewModel.pendingChat.collectAsState()
    val pendingHere = pending.filter { it.receiver == friend.uid && messages.none { message -> message.clientToken == it.token } }
    val isChatLoading by viewModel.isChatLoading.collectAsState()
    val isLoadingOlderChat by viewModel.isLoadingOlderChat.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val myUid = currentUser.uid
    val listState = rememberLazyListState()
    val newestFirst = remember(messages) { messages.asReversed() }
    val currentMessages by rememberUpdatedState(messages)

    val displayName = friend.displayName.ifBlank { friend.username.ifBlank { friend.uid } }
    val headerName = displayName.trim().substringBefore(' ').ifBlank { displayName }
    val moments by viewModel.moments.collectAsState()
    val momentsById = remember(moments) { moments.associateBy { it.id } }
    val density = LocalDensity.current
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var headerHeight by remember(friend.uid) { mutableStateOf(0.dp) }
    var composerHeight by remember(friend.uid) { mutableStateOf(0.dp) }
    val topPadding = headerHeight.takeIf { it > 0.dp } ?: (statusInset + 84.dp)
    val bottomPadding = composerHeight.takeIf { it > 0.dp } ?: 90.dp

    // Conversation detail uses only genuine attached moment thumbnails.
    val conversationUrl = remember(messages, activeConversationId) {
        messages.asReversed().firstOrNull { it.conversationId == activeConversationId && !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
    }
    val ambient = key(friend.uid) { rememberAmbientPalette(conversationUrl, AmbientMode.CONVERSATION) }
    val chatColor = LocketBlack
    val bubbleColor = lerp(ambient.middle, Color.White, 0.12f)
    val sentColor = Color(0xFFF2F0EF)
    // One continuous, softly tinted surface; reuse the animated conversation
    // palette rather than a fixed gray or another image extraction/render pass.
    val composerBrush = remember(ambient.middle, ambient.bottom) {
        val tint = lerp(ambient.bottom, ambient.middle, 0.55f)
        Brush.verticalGradient(
            colors = listOf(
                lerp(tint, Color.White, 0.135f).copy(alpha = 0.82f),
                lerp(tint, Color.White, 0.085f).copy(alpha = 0.82f)
            )
        )
    }
    val composerShape = RoundedCornerShape(34.dp)
    val chromeStops = floatArrayOf(0f, 0.2667f, 0.4667f, 0.6667f, 0.8667f, 1f)
    val topChrome = remember(ambient.top) {
        val alpha = floatArrayOf(1f, 0.823f, 0.676f, 0.52f, 0.18f, 0f)
        Brush.verticalGradient(*Array(chromeStops.size) { chromeStops[it] to ambient.top.copy(alpha = alpha[it]) })
    }
    val bottomChrome = remember(ambient.bottom) {
        val alpha = floatArrayOf(0f, 0.111f, 0.332f, 0.576f, 0.723f, 1f)
        Brush.verticalGradient(*Array(chromeStops.size) { chromeStops[it] to ambient.bottom.copy(alpha = alpha[it]) })
    }

    fun sendText(text: String, clearInput: Boolean = false) {
        if (text.isBlank() || sending) return
        sending = true
        if (clearInput && inputMessage == text) inputMessage = ""
        viewModel.sendChatMessage(friend.uid, text,
            onSuccess = {
                sending = false
            },
            onError = { err ->
                sending = false
                if (inputMessage.isBlank()) inputMessage = text
                Toast.makeText(context, "Không gửi được: $err", Toast.LENGTH_LONG).show()
            })
    }

    // Mo man chat: tim cuoc tro chuyen that theo uid ban, tai tin that.
    DisposableEffect(friend.uid) {
        viewModel.openChat(friend.uid)
        onDispose { viewModel.closeChat() }
    }

    // Reverse layout anchors the newest message at the composer even when IME
    // changes the viewport or the last item is a large square photo.
    LaunchedEffect(messages.lastOrNull()?.id, pendingHere.lastOrNull()?.token) {
        if (messages.isNotEmpty() || pendingHere.isNotEmpty()) {
            listState.scrollToItem(0)
            chatSettled = true
        }
    }

    // Live deltas are primary. The ViewModel reconnects and fetches missed messages on failure.

    // Older messages append at the far end of the reversed list. Stable
    // message keys preserve the current scroll position without index math.
    LaunchedEffect(activeConversationId, chatSettled) {
        if (!chatSettled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val count = currentMessages.size
            count > 0 && info.visibleItemsInfo.any { it.index >= count - 2 } &&
                info.visibleItemsInfo.size < count
        }.collect { nearOldest ->
            val convId = viewModel.activeConversationId.value ?: return@collect
            if (nearOldest && viewModel.chatHasMoreOlder.value && !viewModel.isLoadingOlderChat.value) {
                viewModel.loadOlderChatMessages(convId)
            }
        }
    }

    fun formatTime(createdAtMs: Long): String {
        if (createdAtMs <= 0L) return ""
        val date = java.time.Instant.ofEpochMilli(createdAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return if (date == java.time.LocalDate.now()) "Hôm nay, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(createdAtMs))
        else SimpleDateFormat("HH:mm d 'thg' M", Locale.getDefault()).format(Date(createdAtMs))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ambient.brush())
            // Apply the larger bottom inset exactly once. The window resizes rather than pans.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
    ) {
        Box(Modifier.fillMaxSize()) {
            // Message Stream: tin nhan that tu server (cache Room + sync)
            if (isChatLoading && messages.isEmpty() && pendingHere.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = LocketYellow)
                }
            } else if (messages.isEmpty() && pendingHere.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
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
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pendingHere.asReversed(), key = { "pending:" + it.token }) { draft ->
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            Text(draft.text, color = Color.Black, fontSize = 17.sp,
                                modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(sentColor)
                                    .padding(horizontal = 18.dp, vertical = 12.dp))
                            Text(if (draft.acknowledged) "Đã gửi · Đang đồng bộ" else "Đang gửi…",
                                color = LocketTextDim, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp, top = 3.dp))
                        }
                    }
                    items(newestFirst, key = { it.id }) { msg ->
                        val timeStr = formatTime(msg.createdAtMs)
                        if (msg.thumbnailUrl != null) {
                            // Shared Moment Card in Chat (tin kem moment that)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (timeStr.isNotBlank()) {
                                    Text(
                                        text = timeStr,
                                        color = Color.White.copy(alpha = 0.42f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                ReactionAnchor(
                                    reactions = if (msg.text.isBlank()) msg.reactions else emptyList(),
                                    badgeAtStart = false,
                                    insetBadge = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onReactionClick = { reactingTo = msg }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1.0f)
                                            .clip(RoundedCornerShape(42.dp))
                                            .background(LocketSheetSurface)
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

                                        val linkedMoment = msg.replyMomentUid?.let { momentsById[it] }
                                        // Prefer real moment metadata when available locally.
                                        Row(
                                            modifier = Modifier
                                                .padding(24.dp)
                                                .clip(CircleShape).background(Color.Black.copy(alpha = 0.46f))
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                                .align(Alignment.TopStart),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val senderAvatar = linkedMoment?.userAvatarUrl?.takeIf { it.isNotBlank() }
                                                ?: when (linkedMoment?.userUid) {
                                                    myUid -> currentUser.profilePictureUrl
                                                    friend.uid -> friend.avatarUrl
                                                    null -> if (msg.isMe) currentUser.profilePictureUrl else friend.avatarUrl
                                                    else -> ""
                                                }
                                            ChatAvatar(senderAvatar, 24)
                                            Text(
                                                text = when {
                                                    linkedMoment?.userUid == myUid -> "Bạn"
                                                    linkedMoment != null -> linkedMoment.userName.ifBlank { linkedMoment.userUid }
                                                    msg.isMe -> "Bạn"
                                                    else -> displayName
                                                },
                                                color = Color.White,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f, fill = false),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(chatMomentAge(linkedMoment?.timestampSeconds?.times(1000L) ?: msg.createdAtMs), color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                        linkedMoment?.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                                            Text(caption, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                                                    .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.35f))
                                                    .padding(horizontal = 12.dp, vertical = 6.dp))
                                        }
                                    }
                                }
                                // Text kem theo moment (neu co)
                                if (msg.text.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    MessageBubble(msg = msg, bubbleColor = bubbleColor, sentColor = sentColor, onLongClick = { reactingTo = msg })
                                }
                            }
                        } else if (msg.isMe) {
                            MessageBubble(msg = msg, bubbleColor = bubbleColor, sentColor = sentColor,
                                onLongClick = { reactingTo = msg })
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ChatAvatar(friend.avatarUrl, 32)
                                MessageBubble(msg = msg, bubbleColor = bubbleColor, sentColor = sentColor,
                                    modifier = Modifier.weight(1f), onLongClick = { reactingTo = msg })
                            }
                        }
                    }
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
                }
            }

            // Floating composer above the message stream.
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                    .background(bottomChrome)
                    .padding(top = 12.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(composerShape)
                    .background(composerBrush)
                    .border(0.5.dp, Color.White.copy(alpha = 0.055f), composerShape)
                    .heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = inputMessage,
                    onValueChange = { inputMessage = it },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        fontFamily = LocketFontFamily
                    ),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendText(inputMessage, true) }),
                    decorationBox = { inner ->
                        Box {
                            if (inputMessage.isEmpty()) Text("Gửi tin nhắn...", color = Color.White.copy(alpha = 0.60f), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            inner()
                        }
                    }
                )
                if (inputMessage.isNotBlank()) {
                    IconButton(onClick = { sendText(inputMessage, true) }, enabled = !sending, modifier = Modifier.size(36.dp)) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = if (sending) 0.4f else 0.9f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ArrowUpward, "Gửi", tint = chatColor, modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    for (emoji in listOf("🔥", "😍", "💛")) {
                        Box(Modifier.size(34.dp).clip(CircleShape).clickable(enabled = !sending) { sendText(emoji) }, contentAlignment = Alignment.Center) {
                            Text(emoji, fontSize = 27.sp)
                        }
                    }
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        showQuickEmojis = true
                    }, enabled = !sending, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.AddReaction, "Thêm biểu cảm", tint = Color.White.copy(alpha = 0.55f))
                    }
                }
            }
            // Floating header above the message stream.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter).fillMaxWidth()
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                    .background(topChrome)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 10.dp).padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(LocketAvatarBg),
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
                    text = headerName,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

        }

    }

    if (showQuickEmojis) {
        MomentEmojiSheet(onDismiss = { showQuickEmojis = false }, onPick = {
            showQuickEmojis = false
            if (!sending) sendText(it)
        })
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
            containerColor = LocketModalBg,
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
                                Text("Xóa tin nhắn", color = LocketRed, fontSize = 16.sp)
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { menuConfirm = "report" }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text("Báo cáo tin nhắn", color = LocketRed, fontSize = 16.sp)
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
                        Text("Thu hồi", color = LocketRed, fontWeight = FontWeight.Bold)
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
                        Text("Báo cáo", color = LocketRed, fontWeight = FontWeight.Bold)
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
private fun MessageBubble(
    msg: ChatMessage,
    bubbleColor: Color,
    sentColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onLongClick: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start
    ) {
        ReactionAnchor(
            reactions = msg.reactions,
            badgeAtStart = msg.isMe,
            onReactionClick = onLongClick
        ) {
            val shape = RoundedCornerShape(28.dp)
            Text(
                text = msg.text,
                color = if (msg.isMe) Color.Black else Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(shape)
                    .background(if (msg.isMe) sentColor else bubbleColor)
                    .then(if (msg.isMe) Modifier else Modifier.border(0.5.dp, Color.White.copy(alpha = 0.06f), shape))
                    .combinedClickable(onClick = {}, onLongClick = onLongClick)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

/** Keep the real reaction attached to its message, outside the bubble's clip. */
@Composable
private fun ReactionAnchor(
    reactions: List<MessageReaction>,
    badgeAtStart: Boolean,
    onReactionClick: () -> Unit,
    modifier: Modifier = Modifier,
    insetBadge: Boolean = false,
    content: @Composable () -> Unit
) {
    val visible = reactions.filter { it.emoji.isNotBlank() }
    val hasReaction = visible.isNotEmpty()
    val sideSpace = if (hasReaction && !insetBadge) 8.dp else 0.dp
    Box(modifier.padding(
        start = if (badgeAtStart) sideSpace else 0.dp,
        end = if (badgeAtStart) 0.dp else sideSpace
    )) {
        Box(Modifier.padding(top = if (hasReaction) 18.dp else 0.dp)) {
            content()
        }
        if (hasReaction) {
            Row(
                modifier = Modifier
                    .align(if (badgeAtStart) Alignment.TopStart else Alignment.TopEnd)
                    .offset(x = if (insetBadge) (-8).dp else if (badgeAtStart) (-8).dp else 8.dp)
                    .defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF2F0EF))
                    .clickable(onClick = onReactionClick)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)
            ) {
                visible.take(3).forEach { reaction ->
                    Text(reaction.emoji, fontSize = 17.sp, maxLines = 1)
                }
                if (visible.size > 3) Text("+${visible.size - 3}", color = Color.Black, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ChatAvatar(url: String, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(LocketAvatarBg), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size((sizeDp * 0.55f).dp))
        }
    }
}

private fun chatMomentAge(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val minutes = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "Vừa xong"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h"
        minutes < 10080 -> "${minutes / 1440}d"
        else -> "${minutes / 10080}w"
    }
}
