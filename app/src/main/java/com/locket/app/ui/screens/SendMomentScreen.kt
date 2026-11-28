package com.locket.app.ui.screens

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
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
    showPhotoInfo: Boolean = false,
    captureDetails: String? = null,
    preparedThumbnail: Deferred<ByteArray>? = null,
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
    var infoOpen by remember(bitmap) { mutableStateOf(false) }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var transforming by remember(bitmap) { mutableStateOf(false) }
    val imageTransform = Modifier.graphicsLayer {
        scaleX = zoom
        scaleY = zoom
        translationX = offset.x
        translationY = offset.y
    }
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
            infoOpen -> infoOpen = false
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
                    ReviewActionIcon(ReviewIcon.DOWNLOAD, "Lưu ảnh hoặc video", Color(0xFFE6E6E6), Modifier.size(32.dp))
                }
        }
        },
        frame = {
            val gestureModifier = if (videoFile != null) Modifier else Modifier.pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var drag = Offset.Zero
                    var ownsGesture = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            drag += pan
                            if (fingers >= 2 || (zoom > 1f && drag.getDistance() > viewConfiguration.touchSlop)) {
                                ownsGesture = true
                                transforming = true
                            }
                            if (ownsGesture && fingers > 0) {
                                val next = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val focal = event.calculateCentroid(useCurrent = false)
                                val ratio = next / zoom
                                val moved = if (focal.x.isFinite() && focal.y.isFinite())
                                    (offset - (focal - center)) * ratio + (focal - center) + pan else offset
                                val maxX = size.width * (next - 1f) / 2f
                                val maxY = size.height * (next - 1f) / 2f
                                zoom = next
                                offset = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally { transforming = false }
                }
            }
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).background(Color.Black).then(gestureModifier)) {
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
                        Image(painter, "Ảnh vừa chụp", Modifier.fillMaxSize().then(imageTransform), contentScale = ContentScale.Crop)
                    } else {
                        AsyncImage(
                            model = remember(snapshotFile, context) {
                                ImageRequest.Builder(context).data(Uri.fromFile(snapshotFile))
                                    .crossfade(false).build()
                            },
                            contentDescription = "Ảnh vừa chụp",
                            placeholder = painter,
                            error = painter,
                            modifier = Modifier.fillMaxSize().then(imageTransform),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isPosting && !transforming && zoom <= 1f
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
                if (videoFile == null && zoom > 1f) {
                    Text(String.format(Locale.US, "%.1f×", zoom), color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp).clip(CircleShape)
                            .background(Color(0x99000000)).clickable { zoom = 1f; offset = Offset.Zero }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
                if (videoFile == null && showPhotoInfo) {
                    IconButton(onClick = { infoOpen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(40.dp)) {
                        Icon(Icons.Default.ErrorOutline, "Thông tin ảnh", tint = Color.White,
                            modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(0x99000000)))
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
                    ReviewActionIcon(ReviewIcon.CLOSE, "Hủy", Color.White, Modifier.size(34.dp))
                }
                val canSend = !isPosting && !saving && (sendAll || selected.isNotEmpty())
                Box(Modifier.size(98.dp).clip(CircleShape).background(Color(0xFF505050))
                    .clickable(enabled = canSend) { onSend(value(kind), if (sendAll) emptyList() else selected.toList()) },
                    contentAlignment = Alignment.Center) {
                    if (isPosting) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    else ReviewActionIcon(ReviewIcon.SEND, "Gửi", if (canSend) Color.White else Color.Gray, Modifier.size(44.dp))
                }
                IconButton(onClick = { sheet = true }, enabled = !isPosting, modifier = Modifier.size(56.dp)) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        ReviewActionIcon(ReviewIcon.CAPTION, "Chú thích", Color(0xFFE6E6E6), Modifier.fillMaxSize())
                        Text("Aa", color = Color(0xFFE6E6E6), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.offset(x = (-2).dp, y = 2.dp))
                    }
                }
        }
        },
        recipients = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val itemWidth = maxWidth * 0.208f
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(start = maxWidth * 0.118f, end = maxWidth * 0.05f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                item(key = "all-recipients") {
                    RecipientAvatar("Tất cả", "", sendAll, true, !isPosting, itemWidth) {
                        sendAll = true
                        selected = emptySet()
                    }
                }
                items(recipientFriends, key = { it.uid }) { friend ->
                    RecipientAvatar(friend.displayName.ifBlank { friend.username.ifBlank { friend.uid } },
                        friend.avatarUrl, !sendAll && friend.uid in selected, false, !isPosting, itemWidth) {
                        if (sendAll) {
                            sendAll = false
                            selected = setOf(friend.uid)
                        } else selected = if (friend.uid in selected) selected - friend.uid else selected + friend.uid
                    }
                }
                }
            }
        }
    )

    if (infoOpen && showPhotoInfo && videoFile == null) {
        PhotoInfoDialog(bitmap, snapshotFile, preparedThumbnail, captureDetails) { infoOpen = false }
    }
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
    name: String, avatar: String, selected: Boolean, all: Boolean, enabled: Boolean,
    itemWidth: androidx.compose.ui.unit.Dp, onClick: () -> Unit
) {
    val shortName = if (all) name else name.trim().substringBefore(' ')
    Column(Modifier.width(itemWidth).semantics { contentDescription = name }.clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (selected) 44.dp else 35.dp)
                .border(if (selected) 2.5.dp else 1.5.dp,
                    if (selected) LocketYellow else Color(0xFF484848), CircleShape)
                .padding(if (selected) 4.dp else 3.dp)
                .clip(CircleShape).background(Color(0xFF444444)), contentAlignment = Alignment.Center) {
                if (avatar.isNotBlank() && !all) AsyncImage(avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(if (all) Icons.Default.People else Icons.Default.Person, null, tint = Color.White,
                    modifier = Modifier.size(if (selected) 20.dp else 16.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(shortName, color = if (selected) LocketYellow else Color(0xFF747474), fontSize = 16.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp))
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


@Composable
private fun PhotoInfoDialog(
    bitmap: Bitmap, snapshotFile: File?, prepared: Deferred<ByteArray>?,
    captureDetails: String?, onClose: () -> Unit
) {
    val webp by produceState<String>("Đang xử lý…", prepared) {
        value = try {
            if (prepared == null) "Không có dữ liệu" else {
                val bytes = prepared.await()
                withContext(Dispatchers.Default) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes)
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val dimensions = if (options.outWidth > 0 && options.outHeight > 0)
                        "${options.outWidth} × ${options.outHeight} px" else "Không đọc được kích thước"
                    "$dimensions\n${options.outMimeType ?: "Không rõ MIME"} · ${photoByteSize(bytes.size.toLong())}\nMức nén encoder: 83/100\nMD5: $md5"
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "Không đọc được ảnh đã mã hóa" }
    }
    val jpeg by produceState<String>("Đang chuẩn bị JPEG…", snapshotFile) {
        value = if (snapshotFile == null) "Chưa có tệp JPEG lưu ảnh" else withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(snapshotFile.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) "Không đọc được tệp JPEG"
            else "${options.outWidth} × ${options.outHeight} px\n${options.outMimeType ?: "Không rõ MIME"} · ${photoByteSize(snapshotFile.length())}\nMức nén encoder: 85/100"
        }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Thông tin ảnh") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ảnh sau xử lý", fontWeight = FontWeight.Bold)
                Text("${bitmap.width} × ${bitmap.height} px · ${bitmap.width.toLong() * bitmap.height} pixel")
                Text("Bitmap: ${bitmap.config ?: "Không rõ"}\nBộ nhớ: ${photoByteSize(bitmap.byteCount.toLong())}\nAlpha: ${if (bitmap.hasAlpha()) "Có" else "Không"}")
                if (android.os.Build.VERSION.SDK_INT >= 26) Text("Không gian màu: ${bitmap.colorSpace?.name ?: "Không rõ"}")
                captureDetails?.let { Text(it) }
                HorizontalDivider()
                Text("Ảnh lưu (JPEG)", fontWeight = FontWeight.Bold)
                Text(jpeg)
                HorizontalDivider()
                Text("Ảnh chuẩn bị gửi (WebP)", fontWeight = FontWeight.Bold)
                Text(webp)
                Text("WebP được cắt vuông, tối đa 1020 px mỗi cạnh. Zoom xem trước không thay đổi ảnh gửi. Thông số EXIF/cảm biến không có trong bitmap thì không thể khôi phục.", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("Đóng") } })
}

private fun photoByteSize(bytes: Long): String =
    String.format(Locale.US, "%,d byte (%.2f KiB)", bytes, bytes / 1024.0)


private enum class ReviewIcon { DOWNLOAD, CLOSE, SEND, CAPTION }

@Composable
private fun ReviewActionIcon(kind: ReviewIcon, description: String, tint: Color, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = description }) {
        scale(size.width / 48f, size.height / 48f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 3.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 3.8f) {
                drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = width, cap = StrokeCap.Round)
            }
            fun sparkle(x: Float, y: Float, r: Float) {
                val p = Path().apply {
                    moveTo(x, y - r)
                    quadraticTo(x + r * 0.22f, y - r * 0.22f, x + r, y)
                    quadraticTo(x + r * 0.22f, y + r * 0.22f, x, y + r)
                    quadraticTo(x - r * 0.22f, y + r * 0.22f, x - r, y)
                    quadraticTo(x - r * 0.22f, y - r * 0.22f, x, y - r)
                    close()
                }
                drawPath(p, tint)
            }
            when (kind) {
                ReviewIcon.DOWNLOAD -> {
                    val tray = Path().apply {
                        moveTo(7f, 25f); lineTo(7f, 31f)
                        quadraticTo(7f, 42f, 18f, 42f)
                        lineTo(30f, 42f); quadraticTo(41f, 42f, 41f, 31f)
                        lineTo(41f, 25f)
                    }
                    drawPath(tray, tint, style = stroke)
                    line(24f, 7f, 24f, 29f, 5f)
                    line(17f, 21f, 24f, 30f, 5f)
                    line(31f, 21f, 24f, 30f, 5f)
                }
                ReviewIcon.CLOSE -> {
                    line(10f, 10f, 38f, 38f, 4.5f)
                    line(38f, 10f, 10f, 38f, 4.5f)
                }
                ReviewIcon.SEND -> {
                    val plane = Path().apply {
                        moveTo(4f, 19f); lineTo(44f, 4f); lineTo(29f, 44f)
                        lineTo(19f, 31f); close()
                    }
                    drawPath(plane, tint, style = stroke)
                    line(19f, 31f, 44f, 4f, 3.2f)
                }
                ReviewIcon.CAPTION -> {
                    val ring = Path().apply {
                        moveTo(27f, 8f)
                        cubicTo(7f, 3f, 1f, 22f, 6f, 34f)
                        cubicTo(11f, 47f, 36f, 46f, 41f, 30f)
                    }
                    drawPath(ring, tint, style = stroke)
                    sparkle(37f, 8f, 3.7f)
                    sparkle(43f, 18f, 5f)
                }
            }
        }
    }
}
