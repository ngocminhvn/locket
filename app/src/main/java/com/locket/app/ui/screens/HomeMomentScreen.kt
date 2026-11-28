package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clipToBounds
import coil.request.ImageRequest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.locket.app.ui.components.SkeletonAsyncImage
import com.locket.app.model.Friend
import com.locket.app.model.Moment
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.MomentVideo
import com.locket.app.ui.components.*
import com.locket.app.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeMomentScreen(
    viewModel: LocketViewModel,
    currentMoment: Moment?,
    onNavigate: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit,
    headerHeight: Dp,
    footerHeight: Dp,
    reactionEvent: Pair<String, String>?,
    reactionSerial: Int,
    isActive: Boolean = true,
    onZoomChange: (Boolean) -> Unit = {},
    openActionsRequested: Boolean = false,
    onActionsRequestConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val friends by viewModel.friends.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val momentViewCounts by viewModel.momentViewCounts.collectAsState()
    var floatingReaction by remember(currentMoment?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(reactionSerial) {
        if (reactionSerial > 0 && currentMoment?.id == reactionEvent?.first) {
            floatingReaction = reactionEvent?.second
        }
    }

    // Luot xem (Batch 7a): chi tai cho moment CUA MINH (server 401 cho
    // moment cua ban). Loi -> khong co so lieu -> an chip.
    LaunchedEffect(currentMoment?.id, currentUser.uid, isActive) {
        val moment = currentMoment
        if (isActive && moment != null && moment.userUid == currentUser.uid && momentViewCounts[moment.id] == null) {
            viewModel.refreshMomentViews(moment)
        }
    }

    // Menu tuy chon cua moment dang xem: null = dong; "menu" = danh sach
    // muc; "delete"/"report"/"hide"/"block" = hop thoai xac nhan muc do.
    // Mau theo ChatDetailScreen (AlertDialog nen 0xFF1E1E22, chu do cho
    // thao tac nguy hiem). Chi hien muc phu hop: moment cua minh thi chi
    // co "Xoa khoanh khac", moment cua nguoi khac thi Bao cao / An / Chan.
    var menuAction by remember(currentMoment?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(openActionsRequested, isActive, currentMoment?.id) {
        if (openActionsRequested && isActive && currentMoment != null) {
            menuAction = "menu"
            onActionsRequestConsumed()
        }
    }
    var zoom by remember(currentMoment?.id) { mutableFloatStateOf(1f) }
    var pan by remember(currentMoment?.id) { mutableStateOf(Offset.Zero) }
    val zoomCallback by rememberUpdatedState(onZoomChange)
    var pinching by remember(currentMoment?.id) { mutableStateOf(false) }
    LaunchedEffect(isActive) { if (!isActive) { zoom = 1f; pan = Offset.Zero; pinching = false; menuAction = null } }
    androidx.activity.compose.BackHandler(isActive && zoom > 1f) {
        zoom = 1f; pan = Offset.Zero; zoomCallback(false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(headerHeight))

            Spacer(modifier = Modifier.weight(0.86f))

            // Main Moment Squircle Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.0f)
                    .clip(RoundedCornerShape(52.dp))
                    .background(Color(0xFF1E1E1E))
                    .combinedClickable(
                        onLongClick = { if (currentMoment != null && !pinching && zoom <= 1f) menuAction = "menu" },
                        onClick = {
                        if (zoom > 1f) {
                            zoom = 1f
                            pan = Offset.Zero
                            zoomCallback(false)
                            return@combinedClickable
                        }
                        // Bam 1 lan vao moment = tra loi bang anh: mo camera, nguoi nhan
                        // mac dinh la chu cua moment nay. Khong doi moment bang bam nua
                        // (app goc khong lam vay); doi moment bang loc ban be / xem lich su.
                        val moment = currentMoment
                        if (moment != null && moment.userUid != currentUser.uid) {
                            val target = friends.firstOrNull { it.uid == moment.userUid }
                                ?: Friend(
                                    uid = moment.userUid,
                                    username = moment.userUid,
                                    displayName = moment.userName.ifBlank { moment.userUid }
                                )
                            viewModel.setReplyToFriend(target)
                            onNavigate("camera")
                        }
                    })
            ) {
                if (currentMoment != null) {
                    val videoUrl = currentMoment.videoUrl
                    var imageLoading by remember(currentMoment.thumbnailUrl) { mutableStateOf(true) }
                    val imagePx = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                    SkeletonAsyncImage(
                        model = remember(currentMoment.thumbnailUrl, imagePx) {
                            ImageRequest.Builder(context).data(currentMoment.thumbnailUrl).size(imagePx).crossfade(false).build()
                        },
                        contentDescription = "Moment",
                        modifier = Modifier.fillMaxSize()
                            .then(if (videoUrl.isNullOrBlank()) Modifier.pointerInput(currentMoment.id) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var transforming = false
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val pressed = event.changes.count { it.pressed }
                                            if (pressed >= 2) {
                                                if (!transforming) {
                                                    transforming = true
                                                    pinching = true
                                                    zoomCallback(true)
                                                }
                                                val factor = event.calculateZoom()
                                                val next = (zoom * (if (factor.isFinite() && factor > 0f) factor else 1f)).coerceIn(1f, 4f)
                                                val limitX = size.width * (next - 1f) / 2f
                                                val limitY = size.height * (next - 1f) / 2f
                                                val moved = pan + event.calculatePan()
                                                pan = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
                                                zoom = next
                                                event.changes.forEach { it.consume() }
                                            } else if (transforming) event.changes.forEach { it.consume() }
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        if (transforming) {
                                            zoom = 1f
                                            pan = Offset.Zero
                                            pinching = false
                                            zoomCallback(false)
                                        }
                                    }
                                }
                            }.graphicsLayer {
                                scaleX = zoom; scaleY = zoom
                                translationX = pan.x; translationY = pan.y
                            } else Modifier),
                        contentScale = ContentScale.Crop,
                        onLoading = { imageLoading = true },
                        onSuccess = { imageLoading = false },
                        onError = { imageLoading = false }
                    )
                    if (!videoUrl.isNullOrBlank() && isActive && !imageLoading) {
                        MomentVideo(videoUrl, Modifier.fillMaxSize())
                    }

                    if (!pinching) MomentCaption(currentMoment, Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 20.dp))
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

            Spacer(modifier = Modifier.height(25.dp))

            // User info; caption is shown only once inside the image.
            if (currentMoment != null) {
                Row(
                    modifier = Modifier.alpha(if (pinching) 0f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (currentMoment.userUid != currentUser.uid) Box(
                        modifier = Modifier
                            .size(27.dp)
                            .clip(CircleShape)
                            .background(LocketAvatarBg),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentMoment.userAvatarUrl.isNotBlank()) {
                            SkeletonAsyncImage(
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
                        text = if (currentMoment.userUid == currentUser.uid) "Bạn" else currentMoment.userName.trim().substringBefore(' ').ifBlank { currentMoment.userUid },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = if (currentMoment.userUid == currentUser.uid) java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(currentMoment.timestampSeconds * 1000)) else momentAge(currentMoment.timestampSeconds),
                        color = Color.White.copy(alpha = 0.44f),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold
                    )


                }


            }

            Spacer(modifier = Modifier.weight(1f))

            Spacer(Modifier.height(footerHeight))
        }

        // Menu tuy chon cua moment + hop thoai xac nhan (pattern giong
        // ChatDetailScreen: 1 AlertDialog, text doi theo menuAction).
        if (menuAction == "menu" && currentMoment != null) {
            MomentActionsSheet(currentMoment, currentMoment.userUid == currentUser.uid,
                onDismiss = { menuAction = null }, onAction = { menuAction = it })
        }
        if (menuAction != null && menuAction != "menu" && currentMoment != null) {
            val moment = currentMoment
            val isOwn = moment.userUid == currentUser.uid
            val firstName = moment.userName.trim().substringBefore(' ').ifBlank { moment.userUid }
            AlertDialog(
                onDismissRequest = { menuAction = null },
                containerColor = LocketModalBg,
                title = {
                    Text(
                        text = when (menuAction) {
                            "delete" -> "Xóa khoảnh khắc?"
                            "report" -> "Báo cáo khoảnh khắc?"
                            "hide" -> "Ẩn $firstName?"
                            "block" -> "Chặn $firstName?"
                            else -> "Tùy chọn"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    when (menuAction) {
                        // Y nghia 2 lua chon lay tu chuoi goc
                        // delete_photo_globally_confirmation /
                        // delete_photo_confirmation (xem ApiClient).
                        "delete" -> Text(
                            text = "Xóa cho mọi người: ảnh biến mất khỏi lịch sử của bạn và bạn bè không còn xem được nữa.\nChỉ xóa của mình: ảnh chỉ biến mất khỏi lịch sử của bạn, người khác vẫn xem được.",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        "report" -> Text(
                            text = "Khoảnh khắc này sẽ được gửi đi để xem lại. Chủ sở hữu sẽ không được thông báo.",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        "hide" -> Text(
                            text = "Bạn sẽ không xem Locket của người này nữa và họ sẽ không nhận Locket mới bạn gửi. Họ không được thông báo và hai bạn vẫn ở trong danh sách bạn bè của nhau.",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        "block" -> Text(
                            text = "Họ sẽ không thể nhắn tin cho bạn, xem các Locket của bạn hoặc gửi lời mời kết bạn nữa. Locket sẽ không thông báo cho họ khi bạn chặn họ.",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        else -> {}
                    }
                },
                confirmButton = {
                    when (menuAction) {
                        "delete" -> Row {
                            TextButton(onClick = {
                                viewModel.deleteMoment(
                                    moment = moment,
                                    deleteGlobally = false,
                                    onSuccess = {
                                        Toast.makeText(context, "Đã xóa khoảnh khắc khỏi lịch sử của bạn", Toast.LENGTH_SHORT).show()
                                        menuAction = null
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                        menuAction = null
                                    }
                                )
                            }) {
                                Text("Chỉ xóa của mình", color = Color.White)
                            }
                            TextButton(onClick = {
                                viewModel.deleteMoment(
                                    moment = moment,
                                    deleteGlobally = true,
                                    onSuccess = {
                                        Toast.makeText(context, "Đã xóa khoảnh khắc", Toast.LENGTH_SHORT).show()
                                        menuAction = null
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                        menuAction = null
                                    }
                                )
                            }) {
                                Text("Xóa cho mọi người", color = LocketRed, fontWeight = FontWeight.Bold)
                            }
                        }
                        "report" -> TextButton(onClick = {
                            viewModel.reportMoment(
                                moment = moment,
                                onSuccess = {
                                    Toast.makeText(context, "Đã gửi báo cáo", Toast.LENGTH_SHORT).show()
                                    menuAction = null
                                },
                                onError = { err ->
                                    Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                    menuAction = null
                                }
                            )
                        }) {
                            Text("Báo cáo", color = LocketRed, fontWeight = FontWeight.Bold)
                        }
                        "hide" -> TextButton(onClick = {
                            viewModel.hideFriend(
                                userUid = moment.userUid,
                                onSuccess = {
                                    Toast.makeText(context, "Đã ẩn $firstName", Toast.LENGTH_SHORT).show()
                                    menuAction = null
                                },
                                onError = { err ->
                                    Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                    menuAction = null
                                }
                            )
                        }) {
                            Text("Ẩn", color = LocketRed, fontWeight = FontWeight.Bold)
                        }
                        "block" -> TextButton(onClick = {
                            viewModel.blockFriend(
                                userUid = moment.userUid,
                                onSuccess = {
                                    Toast.makeText(context, "Đã chặn $firstName", Toast.LENGTH_SHORT).show()
                                    menuAction = null
                                },
                                onError = { err ->
                                    Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                    menuAction = null
                                }
                            )
                        }) {
                            Text("Chặn", color = LocketRed, fontWeight = FontWeight.Bold)
                        }
                        else -> {}
                    }
                },
                dismissButton = {
                    TextButton(onClick = { menuAction = null }) {
                        Text("Hủy", color = Color.White)
                    }
                }
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

private fun momentAge(timestamp: Long): String {
    val seconds = ((System.currentTimeMillis() / 1000) - timestamp).coerceAtLeast(0)
    return when {
        seconds < 60 -> "vừa xong"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}


/** A single shared reaction bar, outside the vertical pager. */
@Composable
internal fun MomentReactionBar(
    viewModel: LocketViewModel,
    currentMoment: Moment?,
    onReaction: (String) -> Unit
) {
    val context = LocalContext.current
    val currentUser by viewModel.currentUser.collectAsState()
    val sendingReplies by viewModel.sendingMomentReplies.collectAsState()
    val replyKey = currentMoment?.id?.let { currentUser.uid to it }
    val sending = replyKey != null && replyKey in sendingReplies
    var messageText by remember(currentMoment?.id, currentUser.uid) { mutableStateOf("") }
    var showEmoji by remember(currentMoment?.id) { mutableStateOf(false) }
    var showReply by remember(currentMoment?.id, currentUser.uid) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun sendReply() {
        val moment = currentMoment ?: return
        val text = messageText
        if (text.isBlank() || (currentUser.uid to moment.id) in viewModel.sendingMomentReplies.value) return
        viewModel.sendChatMessage(
            receiverUid = moment.userUid,
            message = text,
            momentUid = moment.id,
            onSuccess = {
                if (messageText == text) {
                    messageText = ""
                    showReply = false
                }
            },
            onError = { err ->
                Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
            }
        )
    }
    fun react(emoji: String) {
        currentMoment?.let {
            onReaction(emoji)
            viewModel.reactToMoment(it.id, it.userUid, emoji)
        }
    }
    if (showEmoji) MomentEmojiSheet(onDismiss = { showEmoji = false }, onPick = { react(it); showEmoji = false })
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .height(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.weight(1f).fillMaxHeight().clickable(enabled = currentMoment != null) { showReply = true },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(messageText.ifBlank { "Gửi tin nhắn..." },
                color = Color.White.copy(alpha = if (messageText.isBlank()) 0.44f else 0.9f),
                fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        for (emoji in listOf("🔥", "😍", "💛")) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = currentMoment != null) { react(emoji) },
                contentAlignment = Alignment.Center) {
                Text(emoji, fontSize = 30.sp)
            }
        }
        IconButton(onClick = { showEmoji = true }, enabled = currentMoment != null,
            modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.AddReaction, "Emoji", tint = Color.White.copy(alpha = 0.60f),
                modifier = Modifier.size(28.dp))
        }
    }

    if (showReply && currentMoment != null) {
        MomentReplyDialog(
            moment = currentMoment,
            message = messageText,
            sending = sending,
            onMessageChange = { messageText = it },
            onDismiss = { keyboard?.hide(); showReply = false },
            onSend = { sendReply() }
        )
    }
}

/** A separate window leaves the moment, caption and root navigation in place behind the IME. */
@Composable
private fun MomentReplyDialog(
    moment: Moment,
    message: String,
    sending: Boolean,
    onMessageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.65f) }
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        val firstName = moment.userName.trim().substringBefore(' ').ifBlank { moment.userUid }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onDismiss
            ))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 14.dp)) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(LocketAvatarBg),
                        contentAlignment = Alignment.Center) {
                        if (moment.userAvatarUrl.isNotBlank()) {
                            SkeletonAsyncImage(moment.userAvatarUrl, null, Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Default.Person, null, tint = Color.White,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                    Column {
                        Text("Replying to", color = Color.White.copy(alpha = 0.60f),
                            fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(firstName, color = Color.White, fontSize = 19.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(30.dp))
                        .background(Color(0xFF242422)).padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = message,
                        onValueChange = onMessageChange,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        minLines = 1, maxLines = 4,
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (message.isEmpty()) Text("Trả lời $firstName...",
                                    color = Color.White.copy(alpha = 0.55f), fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    val canSend = message.isNotBlank() && !sending
                    Box(Modifier.size(28.dp).clip(CircleShape)
                        .background(if (canSend) LocketYellow else Color.White.copy(alpha = 0.16f))
                        .clickable(enabled = canSend, onClick = onSend),
                        contentAlignment = Alignment.Center) {
                        if (sending) CircularProgressIndicator(Modifier.size(15.dp),
                            color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Default.ArrowUpward, "Gửi", tint = Color(0xFF242422),
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}


@Composable
internal fun MomentLoadingSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "moment-loading")
    val alpha by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.65f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "skeleton-alpha"
    )
    Box(modifier.background(Brush.linearGradient(listOf(
        Color(0xFF202022), Color(0xFF48484A).copy(alpha = alpha), Color(0xFF202022)
    ))))
}


/** Activity sheet shares the same cached gradient as the moment viewer. */
@Composable
internal fun MomentBackdrop(url: String) {
    AmbientBackground(url, AmbientMode.ACTIVITY)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnMomentActivityBar(viewModel: LocketViewModel, moment: Moment) {
    val viewersByMoment by viewModel.momentViewers.collectAsState()
    val errors by viewModel.momentViewErrors.collectAsState()
    val loading by viewModel.momentViewsLoading.collectAsState()
    val showViews by viewModel.showViewCount.collectAsState()
    var open by remember(moment.id) { mutableStateOf(false) }
    val viewers = viewersByMoment[moment.id]
    if (showViews) {
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                .clickable { open = true; viewModel.refreshMomentViews(moment) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("✦", color = Color.White.copy(alpha = 0.65f), fontSize = 18.sp)
                Text("Hoạt động", color = Color.White.copy(alpha = 0.78f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (!viewers.isNullOrEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-7).dp), verticalAlignment = Alignment.CenterVertically) {
                        viewers.take(3).forEach { ViewerAvatar(it, 32.dp) }
                        if (viewers.size > 3) Box(Modifier.size(32.dp).clip(CircleShape)
                            .background(Color(0xFF595350)), contentAlignment = Alignment.Center) {
                            Text("+${viewers.size - 3}", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    } else {
        Spacer(Modifier.height(56.dp))
    }
    if (open && showViews) {
        ModalBottomSheet(onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LocketSheetBg,
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)) {
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                Box(Modifier.matchParentSize()) {
                    if (moment.thumbnailUrl.isNotBlank()) MomentBackdrop(moment.thumbnailUrl)
                    Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.055f)))
                }
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Hoạt động", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                    if (viewers == null && moment.id in loading) {
                        CircularProgressIndicator(Modifier.padding(24.dp), color = LocketYellow)
                    } else if (viewers == null) {
                        Text(errors[moment.id] ?: "Chưa tải được hoạt động", color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(16.dp))
                        TextButton(onClick = { viewModel.refreshMomentViews(moment) }) { Text("Thử lại", color = LocketYellow) }
                    } else if (viewers.isEmpty()) {
                        Text("Chưa có lượt xem", color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(24.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 550.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            items(viewers, key = { it.uid }) { viewer ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ViewerAvatar(viewer, 44.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(viewer.displayName.trim().substringBefore(' ').ifBlank { viewer.username.ifBlank { viewer.uid } },
                                            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        Text("Đã xem! ✨", color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                        errors[moment.id]?.let { Text("Không làm mới được: $it", color = LocketRed, fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerAvatar(viewer: Friend, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(LocketAvatarBg), contentAlignment = Alignment.Center) {
        if (viewer.avatarUrl.isNotBlank()) {
            SkeletonAsyncImage(viewer.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val initials = viewer.displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                .take(2).joinToString("") { it.take(1).uppercase() }
            if (initials.isNotBlank()) Text(initials, color = Color.LightGray, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            else Icon(Icons.Default.Person, null, tint = Color.LightGray, modifier = Modifier.size(size * 0.55f))
        }
    }
}
