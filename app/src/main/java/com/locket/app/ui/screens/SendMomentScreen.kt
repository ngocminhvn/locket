package com.locket.app.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import android.net.Uri
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.locket.app.model.Friend
import com.locket.app.ui.theme.LocketYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CaptionKind { TEXT, REVIEW, LOCATION, WEATHER, TIME, STREAK, PARTY, OOTD, MISS }

internal class MomentReviewSlots(
    val header: @Composable () -> Unit,
    val frame: @Composable () -> Unit,
    val indicators: @Composable () -> Unit,
    val controls: @Composable () -> Unit,
    val recipients: @Composable () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMomentReviewSlots(
    bitmap: Bitmap,
    previewBitmap: Bitmap = bitmap,
    snapshotFile: File? = null,
    videoFile: File?,
    friends: List<Friend>,
    replyToFriend: Friend?,
    streak: Int,
    isPosting: Boolean,
    onCancel: () -> Unit,
    onSend: (String, List<String>) -> Unit
): MomentReviewSlots {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recipientFriends = remember(friends, replyToFriend) {
        (listOfNotNull(replyToFriend) + friends).filter { it.uid.isNotBlank() }.distinctBy { it.uid }
    }
    var sendAll by remember(bitmap) { mutableStateOf(replyToFriend == null) }
    var selected by remember(bitmap) { mutableStateOf(listOfNotNull(replyToFriend?.uid).toSet()) }
    var text by remember(bitmap) { mutableStateOf("") }
    var location by remember(bitmap) { mutableStateOf("") }
    var weather by remember(bitmap) { mutableStateOf("") }
    var rating by remember(bitmap) { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CaptionKind?>(null) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val time = remember(bitmap) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val general = remember { listOf(CaptionKind.TEXT, CaptionKind.REVIEW, CaptionKind.LOCATION, CaptionKind.WEATHER, CaptionKind.TIME) }
    val pager = rememberPagerState(pageCount = { general.size })
    var special by remember(bitmap) { mutableStateOf<CaptionKind?>(null) }
    val kind = special ?: general[pager.currentPage]

    fun value(k: CaptionKind): String = when (k) {
        CaptionKind.TEXT -> text.trim()
        CaptionKind.REVIEW -> if (rating == 0) "" else "★".repeat(rating) + "☆".repeat(5 - rating)
        CaptionKind.LOCATION -> location.trim().let { if (it.isBlank()) "" else "📍 $it" }
        CaptionKind.WEATHER -> weather.trim()
        CaptionKind.TIME -> time
        CaptionKind.STREAK -> if (streak > 0) "🔥 $streak" else ""
        CaptionKind.PARTY -> "🪩 Party Time!"
        CaptionKind.OOTD -> "🕶 OOTD"
        CaptionKind.MISS -> "🥰 Miss you"
    }

    fun choose(k: CaptionKind) {
        sheet = false
        if (k in general) {
            special = null
            scope.launch { pager.scrollToPage(general.indexOf(k)) }
        } else special = k
        if (k == CaptionKind.TEXT || k == CaptionKind.LOCATION || k == CaptionKind.WEATHER) {
            draft = when (k) {
                CaptionKind.LOCATION -> location
                CaptionKind.WEATHER -> weather
                else -> text
            }
            editor = k
        }
    }

    // System document picker writes real captured bytes, without storage permission.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (videoFile == null) "image/jpeg" else "video/mp4")
    ) { uri ->
        if (uri != null) {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            if (videoFile != null) videoFile.inputStream().use { it.copyTo(out) }
                            else if (snapshotFile?.isFile == true) snapshotFile.inputStream().use { it.copyTo(out) }
                            else check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                        } ?: error("Không mở được tệp")
                    }
                    Toast.makeText(context, "Đã lưu", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Lỗi lưu: ${e.message}", Toast.LENGTH_LONG).show()
                } finally { saving = false }
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            editor != null -> editor = null
            sheet -> sheet = false
            !isPosting && !saving -> onCancel()
        }
    }

    // Review owns content and interactions; CameraScreen owns geometry.
    val slots = MomentReviewSlots(
        header = {
            Box(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
                Text("Gửi đến...", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center))
                IconButton(
                    onClick = { saveLauncher.launch("Locket_${System.currentTimeMillis()}." + if (videoFile == null) "jpg" else "mp4") },
                    enabled = !isPosting && !saving,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(Icons.Default.FileDownload, "Lưu ảnh hoặc video", tint = Color.White, modifier = Modifier.size(32.dp))
                }
        }
        },
        frame = {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (videoFile != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> VideoView(ctx).apply {
                            setVideoPath(videoFile.absolutePath)
                            setOnPreparedListener { player -> player.isLooping = true; start() }
                            setOnErrorListener { _, _, _ ->
                                Toast.makeText(ctx, "Không phát được video", Toast.LENGTH_SHORT).show()
                                true
                            }
                        } },
                        onRelease = { it.stopPlayback() }
                    )
                } else {
                    val painter = remember(previewBitmap) { BitmapPainter(previewBitmap.asImageBitmap()) }
                    if (snapshotFile == null) {
                        Image(painter, "Ảnh vừa chụp", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        AsyncImage(
                            model = remember(snapshotFile, context) {
                                ImageRequest.Builder(context).data(Uri.fromFile(snapshotFile))
                                    .crossfade(false).build()
                            },
                            contentDescription = "Ảnh vừa chụp",
                            placeholder = painter,
                            error = painter,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isPosting
                ) { page ->
                    val item = special ?: general[page]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        val label = value(item).ifBlank {
                            when (item) {
                                CaptionKind.LOCATION -> "Thêm vị trí"
                                CaptionKind.WEATHER -> "Thêm thời tiết"
                                CaptionKind.REVIEW -> "Chọn đánh giá"
                                else -> "Thêm một tin nhắn"
                            }
                        }
                        val shape = RoundedCornerShape(30.dp)
                        val base = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).clip(shape)
                        if (item == CaptionKind.REVIEW) {
                            Row(base.background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                repeat(5) { index ->
                                    IconButton(onClick = { rating = index + 1 }, enabled = !isPosting, modifier = Modifier.size(32.dp)) {
                                        Icon(if (index < rating) Icons.Default.Star else Icons.Default.StarBorder,
                                            "Đánh giá ${index + 1} sao", tint = LocketYellow, modifier = Modifier.size(26.dp))
                                    }
                                }
                            }
                        } else {
                            val color = when (item) {
                                CaptionKind.OOTD -> Color.White
                                CaptionKind.MISS -> Color(0xFFED3338)
                                CaptionKind.STREAK -> LocketYellow
                                else -> Color(0x99000000)
                            }
                            val styled = if (item == CaptionKind.PARTY) base.background(
                                Brush.verticalGradient(listOf(Color(0xFF65F6E0), Color(0xFFEFFF72)))
                            ) else base.background(color)
                            Box(styled.clickable(enabled = !isPosting) {
                                if (item in listOf(CaptionKind.TEXT, CaptionKind.LOCATION, CaptionKind.WEATHER)) choose(item)
                                else sheet = true
                            }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 3,
                                    color = if (item in listOf(CaptionKind.OOTD, CaptionKind.PARTY, CaptionKind.STREAK)) Color.Black else Color.White)
                            }
                        }
                    }
                }
        }
        },
        indicators = {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                general.forEachIndexed { index, k ->
                    Box(Modifier.size(if (special == null && pager.currentPage == index) 8.dp else 6.dp)
                        .clip(CircleShape).background(if (special == null && pager.currentPage == index) Color.White else Color(0xFF505050))
                        .clickable(enabled = !isPosting) {
                            special = null
                            scope.launch { pager.animateScrollToPage(index) }
                        })
                }
        }
        },
        controls = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel, enabled = !isPosting && !saving, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Close, "Hủy", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                val canSend = !isPosting && !saving && (sendAll || selected.isNotEmpty())
                Box(Modifier.size(98.dp).clip(CircleShape).background(Color(0xFF505050))
                    .clickable(enabled = canSend) { onSend(value(kind), if (sendAll) emptyList() else selected.toList()) },
                    contentAlignment = Alignment.Center) {
                    if (isPosting) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    else Icon(Icons.AutoMirrored.Filled.Send, "Gửi", tint = if (canSend) Color.White else Color.Gray, modifier = Modifier.size(44.dp))
                }
                IconButton(onClick = { sheet = true }, enabled = !isPosting, modifier = Modifier.size(56.dp)) {
                    Text("Aa✦", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
        }
        },
        recipients = {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "all-recipients") {
                    RecipientAvatar("Tất cả", "", sendAll, true, !isPosting) {
                        sendAll = true
                        selected = emptySet()
                    }
                }
                items(recipientFriends, key = { it.uid }) { friend ->
                    RecipientAvatar(friend.displayName.ifBlank { friend.username.ifBlank { friend.uid } },
                        friend.avatarUrl, !sendAll && friend.uid in selected, false, !isPosting) {
                        if (sendAll) {
                            sendAll = false
                            selected = setOf(friend.uid)
                        } else selected = if (friend.uid in selected) selected - friend.uid else selected + friend.uid
                    }
                }
        }
        }
    )

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = Color(0xFF242424),
            shape = RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("Chú thích", Modifier.align(Alignment.CenterHorizontally), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Text("General", color = Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionChip("Aa Văn bản") { choose(CaptionKind.TEXT) }
                    CaptionChip("⭐ Review") { choose(CaptionKind.REVIEW) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionChip("📍 Location") { choose(CaptionKind.LOCATION) }
                    CaptionChip("☁ Weather") { choose(CaptionKind.WEATHER) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionChip("◷ $time") { choose(CaptionKind.TIME) }
                    if (streak > 0) CaptionChip("🔥 $streak", LocketYellow, Color.Black) { choose(CaptionKind.STREAK) }
                }
                Spacer(Modifier.height(24.dp))
                Text("Decorative", color = Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionChip("🪩 Party Time!", Color(0xFF8FF8B5), Color.Black) { choose(CaptionKind.PARTY) }
                    CaptionChip("🕶 OOTD", Color.White, Color.Black) { choose(CaptionKind.OOTD) }
                }
                Spacer(Modifier.height(8.dp))
                CaptionChip("🥰 Miss you", Color(0xFFED3338)) { choose(CaptionKind.MISS) }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    editor?.let { edit ->
        AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(when (edit) {
                CaptionKind.LOCATION -> "Nhập vị trí"
                CaptionKind.WEATHER -> "Nhập thời tiết"
                else -> "Thêm một tin nhắn"
            }) },
            text = {
                Column {
                    if (edit != CaptionKind.TEXT) Text("Nhập thủ công; chưa có nguồn dữ liệu tự động đã xác minh.", fontSize = 12.sp)
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = {
                when (edit) {
                    CaptionKind.LOCATION -> location = draft
                    CaptionKind.WEATHER -> weather = draft
                    else -> text = draft
                }
                editor = null
            }) { Text("Xong") } },
            dismissButton = { TextButton(onClick = { editor = null }) { Text("Hủy") } }
        )
    }
    return slots
}

@Composable
private fun RecipientAvatar(
    name: String, avatar: String, selected: Boolean, all: Boolean, enabled: Boolean, onClick: () -> Unit
) {
    Column(Modifier.width(72.dp).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).border(if (selected) 2.5.dp else 1.5.dp,
            if (selected) LocketYellow else Color(0xFF444444), CircleShape).padding(4.dp)
            .clip(CircleShape).background(Color(0xFF444444)), contentAlignment = Alignment.Center) {
            if (avatar.isNotBlank() && !all) AsyncImage(avatar, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (all) Icons.Default.People else Icons.Default.Person, null, tint = Color.White)
        }
        Spacer(Modifier.height(7.dp))
        Text(name, color = if (selected) LocketYellow else Color(0xFF8A8A8A), fontSize = 15.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CaptionChip(
    label: String, background: Color = Color(0xFF303030), foreground: Color = Color.White, onClick: () -> Unit
) {
    Box(Modifier.clip(RoundedCornerShape(28.dp)).background(background).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 11.dp)) {
        Text(label, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
