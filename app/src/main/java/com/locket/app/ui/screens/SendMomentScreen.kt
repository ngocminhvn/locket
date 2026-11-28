package com.locket.app.ui.screens

import com.locket.app.ui.theme.*

import android.graphics.BitmapFactory
import android.Manifest
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
    originalJpeg: ByteArray? = null,
    showPhotoInfo: Boolean = false,
    captureDetails: String? = null,
    preparedThumbnail: Deferred<ByteArray>? = null,
    videoFile: File?,
    friends: List<Friend>,
    replyToFriend: Friend?,
    streak: Int,
    fetchNativeWeather: suspend (Double, Double) -> com.google.gson.JsonObject?,
    isPosting: Boolean,
    onCancel: () -> Unit,
    onSend: (String, List<String>, String) -> Unit
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
    var weather by remember(bitmap) { mutableStateOf(CaptionLocationData.recentWeather()?.label.orEmpty()) }
    var fetchingCaption by remember(bitmap) { mutableStateOf<CaptionKind?>(null) }
    var permissionTarget by remember(bitmap) { mutableStateOf<CaptionKind?>(null) }
    var rating by remember(bitmap) { mutableIntStateOf(0) }
    var reviewComment by remember(bitmap) { mutableStateOf("") }
    var locationFix by remember(bitmap) { mutableStateOf<android.location.Location?>(null) }
    var weatherData by remember(bitmap) { mutableStateOf<CaptionWeather?>(CaptionLocationData.recentWeather()) }
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
    val general = remember(streak) {
        buildList {
            addAll(listOf(CaptionKind.TEXT, CaptionKind.REVIEW, CaptionKind.LOCATION, CaptionKind.WEATHER, CaptionKind.TIME))
            if (streak > 0) add(CaptionKind.STREAK)
            addAll(listOf(CaptionKind.PARTY, CaptionKind.OOTD, CaptionKind.MISS))
        }
    }
    val pager = rememberPagerState(pageCount = { general.size })
    val kind = general[pager.currentPage.coerceIn(general.indices)]

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

    fun overlay(k: CaptionKind): com.google.gson.JsonObject? {
        val label = value(k)
        if (label.isBlank()) return null
        fun preset(id: String, text: String, emoji: String, colors: List<String>, color: String) =
            com.locket.app.model.CaptionOverlays.make("static_content", text, id, colors = colors,
                textColor = color, blur = null, icon = com.locket.app.model.CaptionOverlays.icon("emoji", emoji))
        val o = com.locket.app.model.CaptionOverlays
        return when (k) {
            CaptionKind.TEXT -> o.make("standard", text.trim(), blur = "ultra_thin", maxLines = 4)
            CaptionKind.REVIEW -> o.make("review", "★$rating" + if (reviewComment.isBlank()) "" else " - $reviewComment",
                payload = com.google.gson.JsonObject().apply { addProperty("rating", rating); addProperty("comment", reviewComment) })
            CaptionKind.LOCATION -> locationFix?.let { fix ->
                o.make("location", location, payload = com.google.gson.JsonObject().apply {
                    addProperty("location_name", location)
                    add("location_coords", com.google.gson.JsonArray().apply { add(fix.latitude); add(fix.longitude) })
                })
            }
            CaptionKind.WEATHER -> weatherData?.overlay()
            CaptionKind.TIME -> o.make("time", time, icon = o.icon("sf_symbol", "clock.fill", "#FFFFFFCC"),
                payload = com.google.gson.JsonObject().apply { addProperty("date", System.currentTimeMillis() / 1000) })
            CaptionKind.STREAK -> o.make("streak", streak.toString(), colors = listOf("#FFE38B", "#FFB900"),
                textColor = "#191919FF", blur = null, icon = o.icon("sf_symbol", "flame.fill", "#191919FF"))
            CaptionKind.PARTY -> preset("caption:party_time", "Party Time!", "🪩", listOf("#5EFFF5", "#FCFF57"), "#000000E6")
            CaptionKind.OOTD -> preset("caption:ootd", "OOTD", "🕶", listOf("#FFFFFF", "#DBDBDB"), "#101010E6")
            CaptionKind.MISS -> preset("caption:miss_you", "Miss you", "🥰", listOf("#EC0C00", "#FF493F"), "#FFFFFFDB")
        }
    }

    fun refreshCaption(k: CaptionKind) {
        if (fetchingCaption != null) return
        fetchingCaption = k
        scope.launch {
            try {
                val fix = CaptionLocationData.currentLocation(context)
                when (k) {
                    CaptionKind.LOCATION -> {
                        location = CaptionLocationData.placeName(context, fix)
                        locationFix = fix
                    }
                    CaptionKind.WEATHER -> {
                        val data = try {
                            fetchNativeWeather(fix.latitude, fix.longitude)?.let { raw ->
                                val temp = raw.get("temperature").asDouble
                                val condition = raw.get("condition_code").asString.replaceFirstChar { it.lowercaseChar() }
                                val cover = raw.get("cloud_cover").asDouble
                                require(temp.isFinite() && cover.isFinite() && condition.isNotBlank())
                                CaptionWeather(temp, condition, raw.get("daylight").asBoolean, cover.coerceIn(0.0, 1.0))
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null } ?: CaptionLocationData.weather(fix)
                        CaptionLocationData.rememberWeather(data)
                        weatherData = data
                        weather = data.label
                    }
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Không lấy được dữ liệu. Hãy bấm lại.", Toast.LENGTH_LONG).show()
            } finally {
                fetchingCaption = null
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val target = permissionTarget
        permissionTarget = null
        if (target != null) {
            if (CaptionLocationData.hasPermission(context)) refreshCaption(target)
            else Toast.makeText(context, "Cần quyền vị trí để lấy địa danh hoặc thời tiết. Có thể cấp trong Cài đặt.", Toast.LENGTH_LONG).show()
        }
    }

    fun requestCaption(k: CaptionKind) {
        if (fetchingCaption != null || permissionTarget != null) return
        if (CaptionLocationData.hasPermission(context)) refreshCaption(k)
        else {
            permissionTarget = k
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    fun choose(k: CaptionKind) {
        sheet = false
        if (k in general) scope.launch { pager.scrollToPage(general.indexOf(k)) }
        when (k) {
            CaptionKind.TEXT -> {
                draft = text
                editor = k
            }
            CaptionKind.LOCATION, CaptionKind.WEATHER -> requestCaption(k)
            else -> Unit
        }
    }

    // Export the capture source, never the preview JPEG or upload thumbnail.
    // PreviewView produces a bitmap, so PNG is the lossless source export.
    val saveMime = if (videoFile != null) "video/mp4" else if (originalJpeg != null) "image/jpeg" else "image/png"
    val saveExtension = if (videoFile != null) "mp4" else if (originalJpeg != null) "jpg" else "png"
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(saveMime)
    ) { uri ->
        if (uri != null) {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            if (videoFile != null) videoFile.inputStream().use { it.copyTo(out) }
                            else if (originalJpeg != null) out.write(originalJpeg)
                            else check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                        } ?: error("Không mở được tệp")
                    }
                    Toast.makeText(context, "Đã lưu", Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
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
                    onClick = { saveLauncher.launch("Locket_${System.currentTimeMillis()}.$saveExtension") },
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
                    val item = general[page]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        val label = if (fetchingCaption == item) "Đang cập nhật…" else value(item).ifBlank {
                            when (item) {
                                CaptionKind.LOCATION -> "Lấy vị trí"
                                CaptionKind.WEATHER -> "Lấy thời tiết"
                                CaptionKind.REVIEW -> "Chọn đánh giá"
                                else -> "Thêm một tin nhắn"
                            }
                        }
                        val shape = RoundedCornerShape(30.dp)
                        val base = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).clip(shape)
                        if (item == CaptionKind.REVIEW) {
                            Column(base.background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                                    repeat(5) { index ->
                                        IconButton(onClick = { rating = index + 1 }, enabled = !isPosting, modifier = Modifier.size(28.dp)) {
                                            OverlayReviewStar(fill = if (index < rating) 1f else 0f,
                                                modifier = Modifier.size(21.dp).semantics { contentDescription = "Đánh giá ${index + 1} sao" })
                                        }
                                    }
                                }
                                if (rating > 0) {
                                    Text(reviewComment.ifBlank { "Viết nhận xét…" }, color = Color.White.copy(alpha = if (reviewComment.isBlank()) 0.45f else 1f),
                                        fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                        modifier = Modifier.clickable(enabled = !isPosting) { draft = reviewComment; editor = CaptionKind.REVIEW }.padding(top = 8.dp))
                                }
                            }
                        } else {
                            val data = overlay(item)?.getAsJsonObject("data")
                            val tap = { if (item in listOf(CaptionKind.TEXT, CaptionKind.LOCATION, CaptionKind.WEATHER)) choose(item) else sheet = true }
                            MomentOverlayPill(data, label, Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                                .clickable(enabled = !isPosting, onClick = tap))
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
            val progress = pager.currentPage + pager.currentPageOffsetFraction
            val center = progress.coerceIn(minOf(2f, (general.size - 1) / 2f), maxOf(general.size - 3f, (general.size - 1) / 2f))
            Box(Modifier.width((minOf(5, general.size) * 19).dp).height(20.dp).clip(RoundedCornerShape(1.dp)), contentAlignment = Alignment.Center) {
                general.forEachIndexed { index, _ ->
                    val distance = kotlin.math.abs(index - center)
                    val active = (1f - kotlin.math.abs(progress - index)).coerceIn(0f, 1f)
                    val visible = (3f - distance).coerceIn(0f, 1f)
                    Box(Modifier.offset(x = ((index - center) * 19f).dp)
                        .size((6f + 2f * active).dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = (0.30f + 0.70f * active) * visible))
                        .clickable(enabled = !isPosting && visible > 0f) { scope.launch { pager.animateScrollToPage(index) } })
                }
            }

        },
        controls = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel, enabled = !isPosting && !saving, modifier = Modifier.size(56.dp)) {
                    ReviewActionIcon(ReviewIcon.CLOSE, "Hủy", Color.White, Modifier.size(34.dp))
                }
                val canSend = !isPosting && !saving && !pager.isScrollInProgress && fetchingCaption != kind && permissionTarget != kind && (sendAll || selected.isNotEmpty())
                Box(Modifier.size(98.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (canSend || isPosting) 0.29f else 0.16f))
                    .clickable(enabled = canSend) { onSend(if (kind == CaptionKind.TEXT) text.trim() else "", if (sendAll) emptyList() else selected.toList(), com.google.gson.JsonArray().apply { overlay(kind)?.let { add(it) } }.toString()) },
                    contentAlignment = Alignment.Center) {
                    if (isPosting) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    else ReviewActionIcon(ReviewIcon.SEND, "Gửi", if (canSend) Color.White else Color.White.copy(alpha = 0.42f), Modifier.size(44.dp))
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
        ModalBottomSheet(
            onDismissRequest = { sheet = false },
            containerColor = Color(0xFF222222),
            shape = RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp),
            dragHandle = {
                Box(Modifier.padding(top = 13.dp, bottom = 12.dp)
                    .size(width = 34.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.20f), CircleShape))
            }
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp)) {
                Text("Chú thích", Modifier.align(Alignment.CenterHorizontally), color = Color.White,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Text("General", color = Color.White.copy(alpha = 0.45f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionChip("Aa Văn bản") { choose(CaptionKind.TEXT) }
                    CaptionChip("Review", icon = Icons.Default.Star, iconTint = LocketYellow) { choose(CaptionKind.REVIEW) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionChip("Location", icon = Icons.Default.LocationOn) { choose(CaptionKind.LOCATION) }
                    CaptionChip("Weather", icon = Icons.Default.Cloud) { choose(CaptionKind.WEATHER) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionChip(time, icon = Icons.Default.AccessTime) { choose(CaptionKind.TIME) }
                    if (streak > 0) CaptionChip(
                        streak.toString(), foreground = Color(0xFF191919),
                        icon = Icons.Default.LocalFireDepartment, iconTint = Color(0xFF191919),
                        gradient = Brush.verticalGradient(listOf(Color(0xFFFFE38B), Color(0xFFFFB900)))
                    ) { choose(CaptionKind.STREAK) }
                }
                Spacer(Modifier.height(20.dp))
                Text("Decorative", color = Color.White.copy(alpha = 0.45f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionChip("🪩 Party Time!", foreground = Color.Black,
                        gradient = Brush.verticalGradient(listOf(Color(0xFF65F6E0), Color(0xFFEFFF72)))) { choose(CaptionKind.PARTY) }
                    CaptionChip("🕶 OOTD", foreground = Color(0xFF222222),
                        gradient = Brush.verticalGradient(listOf(Color.White, Color(0xFFDDDDDD)))) { choose(CaptionKind.OOTD) }
                }
                Spacer(Modifier.height(8.dp))
                CaptionChip("🥰 Miss you", gradient = Brush.verticalGradient(
                    listOf(Color(0xFFFF1308), Color(0xFFFF4848)))) { choose(CaptionKind.MISS) }
                Spacer(Modifier.height(14.dp))
                Text("Thời tiết: Locket / Open-Meteo dự phòng · vị trí chỉ lấy khi bạn chọn.",
                    color = Color.White.copy(alpha = 0.46f), fontSize = 11.sp)
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    editor?.let {
        AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(if (editor == CaptionKind.REVIEW) "Viết nhận xét" else "Thêm một tin nhắn") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it.take(if (editor == CaptionKind.REVIEW) 36 else 200) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = {
                if (editor == CaptionKind.REVIEW) reviewComment = draft.trim().take(36) else text = draft
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
    // Warm, pale selection highlight and neutral translucent surfaces like the reference.
    val selectionColor = Color(0xFFFFDF8B)
    Column(Modifier.width(itemWidth).semantics { contentDescription = name }.clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (selected) 44.dp else 35.dp)
                .border(if (selected) 2.5.dp else 1.5.dp,
                    if (selected) selectionColor else Color.White.copy(alpha = 0.20f), CircleShape)
                .padding(if (selected) 4.dp else 3.dp)
                .clip(CircleShape).background(Color.White.copy(alpha = if (selected) 0.25f else 0.12f)), contentAlignment = Alignment.Center) {
                if (avatar.isNotBlank() && !all) AsyncImage(avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(if (all) Icons.Default.People else Icons.Default.Person, null, tint = Color.White,
                    modifier = Modifier.size(if (selected) 18.dp else 16.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(shortName, color = if (selected) selectionColor else Color.White.copy(alpha = 0.44f), fontSize = 16.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Filled five-point star with rounded tips, matching the reference review badge. */
@Composable
private fun ReviewStar(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension * 0.43f
        val path = Path().apply {
            repeat(10) { index ->
                val angle = -Math.PI / 2 + index * Math.PI / 5
                val r = radius * if (index % 2 == 0) 1f else 0.48f
                val x = center.x + (kotlin.math.cos(angle) * r).toFloat()
                val y = center.y + (kotlin.math.sin(angle) * r).toFloat()
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, tint)
        drawPath(path, tint, style = Stroke(width = size.minDimension * 0.065f,
            cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun CaptionChip(
    label: String,
    background: Color = Color(0xFF2E2E2E),
    foreground: Color = Color.White,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = Color.White.copy(alpha = 0.80f),
    gradient: Brush? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.clip(CircleShape)
            .then(if (gradient != null) Modifier.background(gradient) else Modifier.background(background))
            .clickable(onClick = onClick)
            .heightIn(min = 42.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(23.dp))
        Text(label, color = foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
