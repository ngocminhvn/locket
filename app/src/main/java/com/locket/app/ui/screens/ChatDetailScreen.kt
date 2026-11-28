package com.locket.app.ui.screens

import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Block
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    var reactingTo by remember(friend.uid) { mutableStateOf<ChatMessage?>(null) }
    var menuAnchor by remember(friend.uid) { mutableStateOf<Rect?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var showReactionPicker by remember { mutableStateOf(false) }
    var menuBusy by remember { mutableStateOf(false) }
    val messageBounds = remember(friend.uid) { mutableMapOf<String, Rect>() }
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
    fun openMessageMenu(msg: ChatMessage) {
        if (menuBusy) return
        focusManager.clearFocus()
        keyboardController?.hide()
        menuAnchor = messageBounds[msg.id]
        reactingTo = msg
        menuConfirm = null
    }
    fun dismissMessageMenu() {
        reactingTo = null
        menuConfirm = null
        showReactionPicker = false
        menuAnchor = null
    }
    fun copyMessage(msg: ChatMessage) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Tin nhắn", msg.text))
        dismissMessageMenu()
    }
    fun reactToMessage(msg: ChatMessage, emoji: String) {
        val convId = activeConversationId ?: return
        if (msg.isMe || menuBusy) return
        menuBusy = true
        dismissMessageMenu()
        viewModel.reactToChatMessage(
            conversationId = convId, messageId = msg.id, emoji = emoji,
            onSuccess = { menuBusy = false },
            onRemoved = { menuBusy = false },
            onError = { menuBusy = false; Toast.makeText(context, "Lỗi: $it", Toast.LENGTH_LONG).show() }
        )
    }
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
        Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }) {
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
                                    onReactionClick = { openMessageMenu(msg) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1.0f)
                                            .onGloballyPositioned { if (msg.text.isBlank()) messageBounds[msg.id] = it.boundsInRoot() }
                                            .clip(RoundedCornerShape(42.dp))
                                            .background(LocketSheetSurface)
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = { openMessageMenu(msg) }
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
                                        linkedMoment?.let {
                                            MomentCaption(it, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 20.dp))
                                        }
                                    }
                                }
                                // Text kem theo moment (neu co)
                                if (msg.text.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    MessageBubble(msg = msg, bubbleColor = bubbleColor, sentColor = sentColor, onBounds = { messageBounds[msg.id] = it }, onLongClick = { openMessageMenu(msg) })
                                }
                            }
                        } else if (msg.isMe) {
                            MessageBubble(msg = msg, bubbleColor = bubbleColor, sentColor = sentColor,
                                onBounds = { messageBounds[msg.id] = it }, onLongClick = { openMessageMenu(msg) })
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ChatAvatar(friend.avatarUrl, 32)
                                MessageBubble(msg = msg, bubbleColor = bubbleColor, sentColor = sentColor,
                                    modifier = Modifier.weight(1f), onBounds = { messageBounds[msg.id] = it }, onLongClick = { openMessageMenu(msg) })
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

            val incomingTarget = reactingTo?.takeUnless { it.isMe }
            if (incomingTarget != null && menuConfirm == null && !showReactionPicker) {
                val currentTarget = messages.firstOrNull { it.id == incomingTarget.id } ?: incomingTarget
                val anchor = menuAnchor?.let {
                    Rect(it.left - rootOrigin.x, it.top - rootOrigin.y, it.right - rootOrigin.x, it.bottom - rootOrigin.y)
                }
                val menuTint = lerp(ambient.middle, Color.White, 0.19f)
                ReceivedMessageMenu(
                    anchor = anchor,
                    isImage = currentTarget.text.isBlank() && currentTarget.thumbnailUrl != null,
                    tint = menuTint,
                    canCopy = currentTarget.text.isNotBlank(),
                    canReact = !activeConversationId.isNullOrBlank() && !menuBusy,
                    selectedEmoji = currentTarget.reactions.firstOrNull { it.senderUid == myUid }?.emoji,
                    onDismiss = { dismissMessageMenu() },
                    onCopy = { copyMessage(currentTarget) },
                    onReport = { menuConfirm = "report" },
                    onReact = { reactToMessage(currentTarget, it) },
                    onMore = { showReactionPicker = true }
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

    BackHandler(enabled = reactingTo != null && menuConfirm == null && !showReactionPicker) {
        dismissMessageMenu()
    }

    val target = reactingTo
    if (target != null) {
        if (showReactionPicker && !target.isMe) {
            MomentEmojiSheet(onDismiss = { showReactionPicker = false }, onPick = {
                showReactionPicker = false
                reactToMessage(target, it)
            })
        } else if (menuConfirm != null) {
            val deleting = menuConfirm == "delete"
            AlertDialog(
                onDismissRequest = { if (!menuBusy) dismissMessageMenu() },
                containerColor = LocketModalBg,
                title = { Text(if (deleting) "Thu hồi tin nhắn?" else "Báo cáo tin nhắn?", color = Color.White) },
                text = { Text(if (deleting) "Tin nhắn này sẽ bị thu hồi." else "Tin nhắn này sẽ được gửi báo cáo.", color = Color.White) },
                confirmButton = {
                    TextButton(enabled = !menuBusy && !activeConversationId.isNullOrBlank(),
                        onClick = {
                            val convId = activeConversationId ?: return@TextButton
                            if (deleting != target.isMe) return@TextButton
                            menuBusy = true
                            val success: () -> Unit = {
                                menuBusy = false
                                Toast.makeText(context, if (deleting) "Đã thu hồi tin nhắn" else "Đã gửi báo cáo", Toast.LENGTH_SHORT).show()
                                dismissMessageMenu()
                            }
                            val failure: (String) -> Unit = { error ->
                                menuBusy = false
                                Toast.makeText(context, "Lỗi: $error", Toast.LENGTH_LONG).show()
                                dismissMessageMenu()
                            }
                            if (deleting) viewModel.deleteChatMessage(target.id, convId, success, failure)
                            else viewModel.reportChatMessage(target.id, convId, success, failure)
                        }) { Text(if (deleting) "Thu hồi" else "Báo cáo", color = LocketRed) }
                },
                dismissButton = { TextButton(enabled = !menuBusy, onClick = { dismissMessageMenu() }) { Text("Hủy") } }
            )
        } else if (target.isMe) {
            ModalBottomSheet(
                onDismissRequest = { dismissMessageMenu() },
                containerColor = Color(0xFF242424),
                shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
                dragHandle = null
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    if (target.text.isNotBlank()) MessageSheetAction("Sao chép") { copyMessage(target) }
                    if (!activeConversationId.isNullOrBlank() && target.id.isNotBlank()) {
                        MessageSheetAction("Thu hồi", LocketRed) { menuConfirm = "delete" }
                    }
                    MessageSheetAction("Hủy") { dismissMessageMenu() }
                }
            }
        }
    }
}

@Composable
private fun MessageSheetAction(label: String, color: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Screen-fixed, anchored menu for received messages. The selected bubble stays undimmed. */
@Composable
private fun ReceivedMessageMenu(
    anchor: Rect?, isImage: Boolean, tint: Color, canCopy: Boolean, canReact: Boolean,
    selectedEmoji: String?, onDismiss: () -> Unit, onCopy: () -> Unit,
    onReport: () -> Unit, onReact: (String) -> Unit, onMore: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val gap = with(density) { 8.dp.toPx() }
        val barHeight = with(density) { 64.dp.toPx() }
        val menuHeight = with(density) { (if (canCopy) 104.dp else 52.dp).toPx() }
        val barWidth = (maxWidth - 24.dp).coerceAtMost(310.dp)
        val menuWidth = (maxWidth - 24.dp).coerceAtMost(202.dp)
        val barPx = with(density) { barWidth.toPx() }
        val menuPx = with(density) { menuWidth.toPx() }
        val top = anchor?.top ?: h * 0.42f
        val bottom = anchor?.bottom ?: h * 0.50f
        val left = anchor?.left ?: gap
        val safeTop = with(density) { 48.dp.toPx() }
        val barY = (top - gap - barHeight).coerceIn(safeTop.coerceAtMost((h - barHeight).coerceAtLeast(0f)), (h - barHeight).coerceAtLeast(0f))
        val menuY = (bottom + gap).coerceIn(0f, (h - menuHeight - gap).coerceAtLeast(0f))
        val barX = left.coerceIn(gap.coerceAtMost((w - barPx).coerceAtLeast(0f)), (w - barPx - gap).coerceAtLeast(gap.coerceAtMost((w - barPx).coerceAtLeast(0f))))
        val menuX = left.coerceIn(0f, (w - menuPx - gap).coerceAtLeast(0f))
        Canvas(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss
        )) {
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                anchor?.let {
                    val radius = (if (isImage) 42.dp else 28.dp).toPx().coerceAtMost(it.height / 2f)
                    if (it.width > 0f && it.height > 0f) addRoundRect(RoundRect(it, CornerRadius(radius, radius)))
                }
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.48f))
        }
        val surface = tint.copy(alpha = 0.98f)
        if (canReact) Row(
            Modifier.offset { IntOffset(barX.roundToInt(), barY.roundToInt()) }
                .width(barWidth).height(64.dp).clip(CircleShape).background(surface)
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val defaults = listOf("🫶", "💕", "😍", "🤣", "💛")
            val emojis = if (selectedEmoji != null && selectedEmoji !in defaults) defaults.dropLast(1) + selectedEmoji else defaults
            emojis.forEach { emoji ->
                Box(Modifier.weight(1f).height(48.dp).clip(CircleShape)
                    .background(if (emoji == selectedEmoji) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onReact(emoji) }, contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 27.sp, maxLines = 1)
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.AddReaction, "Thêm biểu cảm", tint = Color.White.copy(alpha = 0.60f), modifier = Modifier.size(29.dp))
            }
        }
        Column(
            Modifier.offset { IntOffset(menuX.roundToInt(), menuY.roundToInt()) }
                .width(menuWidth).clip(RoundedCornerShape(18.dp)).background(surface)
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
        ) {
            if (canCopy) {
                Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onCopy).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.ContentCopy, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text("Sao chép", color = Color.White, fontSize = 16.sp)
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f), modifier = Modifier.padding(horizontal = 12.dp))
            }
            Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onReport).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Block, null, tint = LocketRed, modifier = Modifier.size(20.dp))
                Text("Báo cáo tin nhắn", color = LocketRed, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    bubbleColor: Color,
    sentColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onBounds: (Rect) -> Unit = {},
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
                modifier = Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) }.clip(shape)
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
