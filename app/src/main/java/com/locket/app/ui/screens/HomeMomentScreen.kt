package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.model.Friend
import com.locket.app.model.Moment
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.*
import com.locket.app.ui.theme.*

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
    reactionSerial: Int
) {
    val context = LocalContext.current
    val friends by viewModel.friends.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val momentViewCounts by viewModel.momentViewCounts.collectAsState()
    val showViewCount by viewModel.showViewCount.collectAsState()
    var floatingReaction by remember(currentMoment?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(reactionSerial) {
        if (reactionSerial > 0 && currentMoment?.id == reactionEvent?.first) {
            floatingReaction = reactionEvent?.second
        }
    }

    // Luot xem (Batch 7a): chi tai cho moment CUA MINH (server 401 cho
    // moment cua ban). Loi -> khong co so lieu -> an chip.
    LaunchedEffect(currentMoment?.id, currentUser.uid) {
        val moment = currentMoment
        if (moment != null && moment.userUid == currentUser.uid) {
            viewModel.refreshMomentViews(moment)
        }
    }

    // Menu tuy chon cua moment dang xem: null = dong; "menu" = danh sach
    // muc; "delete"/"report"/"hide"/"block" = hop thoai xac nhan muc do.
    // Mau theo ChatDetailScreen (AlertDialog nen 0xFF1E1E22, chu do cho
    // thao tac nguy hiem). Chi hien muc phu hop: moment cua minh thi chi
    // co "Xoa khoanh khac", moment cua nguoi khac thi Bao cao / An / Chan.
    var menuAction by remember(currentMoment?.id) { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(headerHeight))

            Spacer(modifier = Modifier.weight(0.8f))

            // Main Moment Squircle Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.0f)
                    .clip(RoundedCornerShape(48.dp))
                    .background(Color(0xFF1E1E1E))
                    .clickable {
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
                    }
            ) {
                if (currentMoment != null) {
                    val videoUrl = currentMoment.videoUrl
                    if (!videoUrl.isNullOrBlank()) {
                        key(videoUrl) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx -> VideoView(ctx).apply {
                                    setVideoURI(android.net.Uri.parse(videoUrl))
                                    setOnPreparedListener { player -> player.isLooping = true; start() }
                                } },
                                onRelease = { it.stopPlayback() }
                            )
                        }
                    } else AsyncImage(
                        model = currentMoment.thumbnailUrl,
                        contentDescription = "Moment",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // Caption overlay inside photo (Matches frame_010.jpg)
                    if (currentMoment.caption.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                                .liquidGlassEffect(RoundedCornerShape(20.dp), backgroundColor = Color(0xB31E1E20))
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = currentMoment.caption,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
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

                // Nut "..." mo menu tuy chon cua moment (goc co menu tuy
                // chon tren moment). Nut tron mo nhu top-bar hien tai,
                // nen mo giong overlay caption trong anh.
                if (currentMoment != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xB31E1E20))
                            .clickable { menuAction = "menu" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Tùy chọn",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // User info; caption is shown only once inside the image.
            if (currentMoment != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(27.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2C2C2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentMoment.userAvatarUrl.isNotBlank()) {
                            AsyncImage(
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
                        text = currentMoment.userName.trim().substringBefore(' ').ifBlank { currentMoment.userUid },
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = momentAge(currentMoment.timestampSeconds),
                        color = LocketTextDim,
                        fontSize = 16.sp
                    )

                    // Luot xem (Batch 7a): chi moment CUA MINH, chi hien
                    // khi server tra 200 (co so lieu that). Loi/401 -> an.
                    val ownViewCount = momentViewCounts[currentMoment.id]
                    if (showViewCount && currentMoment.userUid == currentUser.uid && ownViewCount != null) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = null,
                            tint = LocketTextDim,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "$ownViewCount lượt xem",
                            color = LocketTextDim,
                            fontSize = 16.sp
                        )
                    }
                }


            }

            Spacer(modifier = Modifier.weight(1f))

            Spacer(Modifier.height(footerHeight))
        }

        // Menu tuy chon cua moment + hop thoai xac nhan (pattern giong
        // ChatDetailScreen: 1 AlertDialog, text doi theo menuAction).
        if (menuAction != null && currentMoment != null) {
            val moment = currentMoment
            val isOwn = moment.userUid == currentUser.uid
            val firstName = moment.userName.trim().substringBefore(' ').ifBlank { moment.userUid }
            AlertDialog(
                onDismissRequest = { menuAction = null },
                containerColor = Color(0xFF1E1E22),
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
                        "menu" -> Column(modifier = Modifier.fillMaxWidth()) {
                            if (isOwn) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { menuAction = "delete" }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text("Xóa khoảnh khắc", color = Color(0xFFFF6B6B), fontSize = 16.sp)
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { menuAction = "report" }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text("Báo cáo", color = Color(0xFFFF6B6B), fontSize = 16.sp)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { menuAction = "hide" }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text("Ẩn bạn này", color = Color.White, fontSize = 16.sp)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { menuAction = "block" }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text("Chặn người này", color = Color(0xFFFF6B6B), fontSize = 16.sp)
                                }
                            }
                        }
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
                                Text("Xóa cho mọi người", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
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
                            Text("Báo cáo", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
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
                            Text("Ẩn", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
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
                            Text("Chặn", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
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
    var messageText by remember(currentMoment?.id) { mutableStateOf("") }
            // Reaction Bar (Calls real reactToMoment API & sends real chat message)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF202022))
                    .padding(horizontal = 10.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Text input
                TextField(
                    value = messageText,
                    onValueChange = { messageText = it },
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

                // Quick emoji buttons calling reactToMoment
                val emojis = listOf("🔥", "😍", "💛")
                for (emoji in emojis) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = currentMoment != null) {
                                onReaction(emoji)
                                if (currentMoment != null) {
                                    viewModel.reactToMoment(
                                        momentId = currentMoment.id,
                                        ownerUid = currentMoment.userUid,
                                        reaction = emoji
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 26.sp)
                    }
                }

                // Send message button if text entered
                if (messageText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            if (currentMoment != null) {
                                // Tra loi moment: gui kem moment_uid that (shape goc
                                // sendChatMessageV2 co moment_uid); chi xoa o nhap
                                // + toast khi server tra 200.
                                val text = messageText
                                viewModel.sendChatMessage(
                                    receiverUid = currentMoment.userUid,
                                    message = text,
                                    momentUid = currentMoment.id,
                                    onSuccess = {
                                        Toast.makeText(context, "Đã gửi tin nhắn!", Toast.LENGTH_SHORT).show()
                                        messageText = ""
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Gửi",
                            tint = LocketYellow,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Chọn biểu cảm", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEmotions,
                            contentDescription = "Emoji",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

}
