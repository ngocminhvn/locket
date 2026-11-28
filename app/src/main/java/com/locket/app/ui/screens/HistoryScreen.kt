package com.locket.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.core.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.filled.Share
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.material.icons.filled.AccessTime
import coil.request.ImageRequest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.locket.app.model.Moment
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.LocketBottomBar
import com.locket.app.ui.components.liquidGlassEffect
import com.locket.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
    viewModel: LocketViewModel,
    onNavigate: (String) -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val allMoments by viewModel.moments.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val moments = remember(allMoments, currentUser.uid) {
        if (currentUser.uid.isBlank()) emptyList() else allMoments.filter { it.userUid == currentUser.uid }
    }
    val historyLoading by viewModel.ownHistoryLoading.collectAsState()
    val historyHasMore by viewModel.ownHistoryHasMore.collectAsState()
    val historyError by viewModel.ownHistoryError.collectAsState()
    val coveredThrough by viewModel.ownHistoryCoveredThrough.collectAsState()
    var viewingMoment by remember { mutableStateOf<Moment?>(null) }

    // Group moments by "year-month-day"
    val momentsByDate = remember(moments) {
        val map = mutableMapOf<String, MutableList<Moment>>()
        val cal = Calendar.getInstance()
        for (m in moments) {
            cal.timeInMillis = m.timestampSeconds * 1000
            val y = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val key = "$y-$month-$d"
            map.getOrPut(key) { mutableListOf() }.add(m)
        }
        map
    }

    val currentMonth = remember { java.time.YearMonth.now() }
    val oldestOwnMonth = remember(moments) {
        moments.minOfOrNull { it.timestampSeconds }?.let {
            java.time.YearMonth.from(java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()))
        }
    }
    val defaultStart = currentMonth.minusMonths(14)
    val firstMonth = minOf(defaultStart, oldestOwnMonth ?: defaultStart)
    val calendarMonths = remember(firstMonth, currentMonth) {
        val months = mutableListOf<CalendarMonthData>()
        var month = firstMonth
        while (month <= currentMonth) {
            months.add(CalendarMonthData(month.year, month.monthValue,
                "Tháng ${month.monthValue} ${month.year}", month.lengthOfMonth()))
            month = month.plusMonths(1)
        }
        months
    }
    // Old -> new, open at the current month and stats, just like the reference.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = calendarMonths.lastIndex)
    val totalCount = moments.size
    val streak = remember(moments) { viewModel.calculateStreak(moments) }
    val transition = rememberInfiniteTransition(label = "memoryShimmer")
    val shimmer by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Restart),
        label = "memoryShimmerProgress"
    )
    val visibleOldest = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
    val oldestVisibleMonth = visibleOldest?.let { key -> calendarMonths.firstOrNull { "${it.year}-${it.month}" == key } }
        ?: calendarMonths.last()
    val neededTs = remember(oldestVisibleMonth) { monthStart(oldestVisibleMonth) }
    val needsMore = viewModel.isLoggedIn && historyHasMore && historyError == null &&
        (coveredThrough == null || coveredThrough!! >= neededTs)
    LaunchedEffect(needsMore, historyLoading, historyError, coveredThrough) {
        if (needsMore && !historyLoading) viewModel.loadOwnHistory()
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 96.dp, bottom = 92.dp)
        ) {
            // Month Cards
            items(calendarMonths, key = { "${it.year}-${it.month}" }) { monthData ->
                val monthLoading = historyHasMore && historyError == null &&
                    (coveredThrough == null || coveredThrough!! >= monthStart(monthData))
                MonthCardDynamic(
                    monthData = monthData,
                    momentsByDate = momentsByDate,
                    isLoading = monthLoading,
                    shimmer = shimmer,
                    onMomentClick = { viewingMoment = it },
                    onTodayAddClick = { onNavigate("camera") }
                )
                MemoryConnector()
            }
            if (historyError != null) {
                item(key = "history-error") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(historyError.orEmpty(), color = Color(0xFFFF6B6B), fontSize = 13.sp)
                        TextButton(onClick = { viewModel.loadOwnHistory() }) { Text("Thử lại", color = LocketYellow) }
                    }
                }
            }

            // Stats Pill: "💛 {totalCount} Locket | 🔥 {streak} ngày chuỗi"
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .liquidGlassEffect(RoundedCornerShape(24.dp), backgroundColor = Color(0xD91E1E20))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (historyHasMore) "💛 $totalCount đã tải" else "💛 $totalCount Locket",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "|",
                        color = Color(0xFF555555),
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (historyHasMore && (coveredThrough == null || coveredThrough!! >= java.time.LocalDate.now().minusDays(streak.toLong() + 1).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond())) "🔥 Đang tải" else "🔥 $streak ngày chuỗi",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Fixed header stays visible while the months scroll underneath.
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(LocketBlack, LocketBlack.copy(alpha = 0.88f), Color.Transparent)))
            .statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).height(48.dp)) {
            Text("Kỷ niệm", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).size(46.dp).clip(CircleShape)
                .background(Color(0xFF2C2C2E)).clickable(onClick = onOpenProfile),
                contentAlignment = Alignment.Center) {
                if (currentUser.profilePictureUrl.isNotBlank()) {
                    AsyncImage(currentUser.profilePictureUrl, "Hồ sơ", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else Icon(Icons.Default.Person, "Hồ sơ", tint = Color.White)
            }
        }

        // Bottom Navigation Bar
        Box(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LocketBottomBar(
                currentScreen = "history",
                onNavigate = onNavigate
            )
        }

        // Memory Full-Screen Viewer Dialog
        viewingMoment?.let { moment ->
            MemoryDetailDialog(
                moment = moment,
                onDismiss = { viewingMoment = null },
                onReact = { emoji ->
                    viewModel.reactToMoment(moment.id, moment.userUid, emoji)
                },
                historyMoments = moments,
                hasMore = historyHasMore,
                loadingOlder = historyLoading,
                historyError = historyError,
                onLoadOlder = { viewModel.loadOwnHistory() },
                onDelete = { target, globally, done ->
                    viewModel.deleteMoment(target, globally, onSuccess = {
                        done()
                        viewingMoment = null
                    }, onError = { error ->
                        done()
                        android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                    })
                }
            )
        }
    }
}

data class CalendarMonthData(
    val year: Int,
    val month: Int,
    val monthTitle: String,
    val daysCount: Int
)

@Composable
private fun MonthCardDynamic(
    monthData: CalendarMonthData,
    momentsByDate: Map<String, List<Moment>>,
    isLoading: Boolean = false,
    shimmer: Float = 0f,
    onMomentClick: (Moment) -> Unit,
    onTodayAddClick: () -> Unit
) {
    val todayCal = Calendar.getInstance()
    val isCurrentMonth = todayCal.get(Calendar.YEAR) == monthData.year &&
            (todayCal.get(Calendar.MONTH) + 1) == monthData.month
    val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)

    val firstDate = java.time.LocalDate.of(monthData.year, monthData.month, 1)
    val offset = firstDate.dayOfWeek.value - 1
    val rows = (offset + monthData.daysCount + 6) / 7
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(24.dp)).background(Color(0xFF141416))
    ) {
        Text(monthData.monthTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF242424)).padding(horizontal = 16.dp, vertical = 12.dp))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(7) { col ->
                        val day = row * 7 + col - offset + 1
                        val valid = day in 1..monthData.daysCount
                        val ownMoment = if (valid) momentsByDate["${monthData.year}-${monthData.month}-$day"]?.firstOrNull() else null
                        val today = isCurrentMonth && day == todayDay
                        val future = isCurrentMonth && day > todayDay
                        val holiday = when {
                            monthData.month == 10 && day == 31 -> "🎃"
                            monthData.month == 12 && day == 25 -> "🎁"
                            monthData.month == 1 && day == 1 -> "🎆"
                            else -> null
                        }
                        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                            when {
                                !valid -> Unit
                                ownMoment != null -> AsyncImage(ownMoment.thumbnailUrl, null,
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)).background(Color(0xFF292929))
                                        .clickable { onMomentClick(ownMoment) }, contentScale = ContentScale.Crop)
                                isLoading && !future -> {
                                    val phase = (shimmer * 2f - (row * 7 + col) / 35f).coerceIn(0f, 1f)
                                    val intensity = (1f - kotlin.math.abs(phase * 2f - 1f)) * 0.55f
                                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                        .background(androidx.compose.ui.graphics.lerp(Color(0xFF191919), Color(0xFF343434), intensity)))
                                }
                                today -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF3B2D11)).border(2.5.dp, LocketYellow, RoundedCornerShape(10.dp))
                                    .clickable(onClick = onTodayAddClick), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, "Thêm khoảnh khắc", tint = LocketYellow, modifier = Modifier.size(24.dp))
                                }
                                future -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0xFF262626)))
                                holiday != null -> Text(holiday, fontSize = 20.sp)
                                else -> Box(Modifier.size(11.dp).background(Color(0xFF303030), CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun monthStart(month: CalendarMonthData): Long =
    java.time.LocalDate.of(month.year, month.month, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()

@Composable
private fun MemoryConnector() {
    Canvas(Modifier.padding(vertical = 8.dp).size(width = 46.dp, height = 16.dp)) {
        val line = Path().apply {
            moveTo(0f, size.height * 0.15f)
            cubicTo(size.width * 0.25f, size.height, size.width * 0.75f, 0f, size.width, size.height * 0.85f)
        }
        drawPath(line, Color(0xFF3A3A3A), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))))
    }
}

@Composable
fun MemoryDetailDialog(
    moment: Moment,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    historyMoments: List<Moment> = emptyList(),
    hasMore: Boolean = false,
    loadingOlder: Boolean = false,
    historyError: String? = null,
    onLoadOlder: () -> Unit = {},
    onDelete: ((Moment, Boolean, () -> Unit) -> Unit)? = null
) {
    val photos = remember(moment.id, historyMoments) {
        (historyMoments + moment).distinctBy { it.id }.sortedByDescending { it.timestampSeconds }
    }
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.id == moment.id }.coerceAtLeast(0)) { photos.size }
    val scope = rememberCoroutineScope()
    val active = photos[pager.currentPage.coerceIn(0, photos.lastIndex)]
    val date = remember(active.timestampSeconds) {
        Calendar.getInstance().apply { timeInMillis = active.timestampSeconds * 1000 }
    }
    val time = remember(active.timestampSeconds) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(active.timestampSeconds * 1000))
    }
    var share by remember(active.id) { mutableStateOf(false) }
    var delete by remember(active.id) { mutableStateOf(false) }
    var deleting by remember(active.id) { mutableStateOf(false) }
    var zoom by remember(active.id) { mutableFloatStateOf(1f) }
    var pan by remember(active.id) { mutableStateOf(Offset.Zero) }
    val thumbs = rememberLazyListState(initialFirstVisibleItemIndex = pager.currentPage)
    LaunchedEffect(active.id, pager.currentPage) {
        zoom = 1f
        pan = Offset.Zero
        if (photos.size > 1) thumbs.animateScrollToItem(pager.currentPage)
    }
    LaunchedEffect(pager.currentPage, photos.size, hasMore, loadingOlder, historyError) {
        if (hasMore && !loadingOlder && historyError == null && pager.currentPage >= (photos.size - 5).coerceAtLeast(0)) onLoadOlder()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
            if (active.thumbnailUrl.isNotBlank()) MemoryBackdrop(active.thumbnailUrl)
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val viewportWidth = maxWidth
                val photoSize = minOf(viewportWidth - 48.dp, maxHeight * 0.48f).coerceAtLeast(1.dp)
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp).height(58.dp)) {
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart).size(44.dp)) {
                        Icon(Icons.Default.Close, "Đóng", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(date.get(Calendar.YEAR).toString(), color = Color.White.copy(alpha = 0.60f),
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("${date.get(Calendar.DAY_OF_MONTH)} tháng ${date.get(Calendar.MONTH) + 1}",
                            color = Color.White.copy(alpha = 0.90f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    IconButton(onClick = { share = true }, modifier = Modifier.align(Alignment.CenterEnd).size(44.dp)) {
                        Icon(Icons.Default.Share, "Chia sẻ", tint = Color.White.copy(alpha = 0.90f), modifier = Modifier.size(26.dp))
                    }
                }

                Column(Modifier.align(Alignment.Center).offset(y = (-31).dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(photoSize),
                        pageSize = PageSize.Fixed(photoSize),
                        contentPadding = PaddingValues(horizontal = (viewportWidth - photoSize) / 2),
                        pageSpacing = 16.dp,
                        beyondViewportPageCount = 1,
                        key = { photos[it].id },
                        userScrollEnabled = zoom <= 1f && !share && !delete) { page ->
                        val photo = photos[page]
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(photoSize).graphicsLayer {
                                val distance = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                                scaleY = 1f - 0.28f * distance
                            }.clip(RoundedCornerShape(46.dp))
                                .background(Color(0xFF1E1E20))) {
                                val selected = page == pager.currentPage
                                AsyncImage(photo.thumbnailUrl, photo.caption,
                                    Modifier.fillMaxSize()
                                        .then(if (selected) Modifier.pointerInput(photo.id) {
                                            awaitEachGesture {
                                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                var ownsGesture = false
                                                do {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    val fingers = event.changes.count { it.pressed }
                                                    if (fingers >= 2 || zoom > 1f) ownsGesture = true
                                                    if (ownsGesture && fingers > 0) {
                                                        val next = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                                                        val limitX = size.width * (next - 1f) / 2f
                                                        val limitY = size.height * (next - 1f) / 2f
                                                        val moved = pan + event.calculatePan()
                                                        pan = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
                                                        zoom = next
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                } while (event.changes.any { it.pressed })
                                            }
                                        }.graphicsLayer {
                                            scaleX = zoom; scaleY = zoom
                                            translationX = pan.x; translationY = pan.y
                                        } else Modifier),
                                    contentScale = ContentScale.Crop)
                                if (zoom <= 1f || !selected) {
                                    val photoTime = remember(photo.timestampSeconds) {
                                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(photo.timestampSeconds * 1000))
                                    }
                                    val timeChip = photo.caption.isBlank() || photo.caption.trim() == photoTime
                                    Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 24.dp)
                                        .background(Color.Black.copy(alpha = 0.44f), CircleShape)
                                        .padding(horizontal = 13.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (timeChip) Icon(Icons.Default.AccessTime, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                                        Text(if (timeChip) photoTime else photo.caption, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (selected && zoom > 1f) {
                                    Text("1×", color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                                        .clickable { zoom = 1f; pan = Offset.Zero }.padding(8.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(time, color = Color.White.copy(alpha = 0.38f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                if (historyError != null && pager.currentPage >= (photos.size - 3).coerceAtLeast(0)) {
                    TextButton(onClick = onLoadOlder, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 94.dp)) { Text("Thử tải ảnh cũ", color = Color.White) }
                }
                LazyRow(
                    state = thumbs,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 23.dp),
                    contentPadding = PaddingValues(horizontal = viewportWidth * 0.4f),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(photos, key = { it.id }) { photo ->
                        val selected = photo.id == active.id
                        val distance = kotlin.math.abs(photos.indexOf(photo) - pager.currentPage)
                        val thumbSize = when (distance) { 0 -> 60.dp; 1 -> 41.dp; else -> 32.dp }
                        Box(Modifier.width(viewportWidth / 5).height(60.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(thumbSize).clip(RoundedCornerShape(if (selected) 18.dp else 10.dp))
                            .border(if (selected) 3.dp else 0.dp, if (selected) Color.White.copy(alpha = 0.90f) else Color.Transparent, RoundedCornerShape(if (selected) 18.dp else 10.dp))
                            .clickable { scope.launch { pager.animateScrollToPage(photos.indexOf(photo)) } }
                            .padding(if (selected) 5.dp else 0.dp)) {
                            AsyncImage(photo.thumbnailUrl, "Ảnh kỷ niệm", Modifier.fillMaxSize().clip(RoundedCornerShape(if (selected) 12.dp else 9.dp)),
                                contentScale = ContentScale.Crop)
                        }
                        }
                    }
                }
            }
        }

        if (share) {
            MomentActionsSheet(active, own = true, onDismiss = { share = false },
                onAction = { action -> share = false; if (action == "delete") delete = true },
                shareOnly = onDelete == null, compact = true)
        }
        if (delete && onDelete != null) {
            AlertDialog(onDismissRequest = { if (!deleting) delete = false },
                title = { Text(if (active.videoUrl.isNullOrBlank()) "Xóa ảnh?" else "Xóa video?") },
                text = { Text("Chỉ xóa khỏi lịch sử của bạn hoặc xóa cho mọi người.") },
                confirmButton = { TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    onDelete(active, true) { deleting = false; delete = false }
                }) { Text("Xóa cho mọi người", color = Color(0xFFFF6B6B)) } },
                dismissButton = { TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    onDelete(active, false) { deleting = false; delete = false }
                }) { Text("Chỉ xóa của mình") } })
        }
    }
}


/** Blur the actual image on a tiny layer before stretching it to the viewer.
 * This gives an even backdrop without extracting a palette or blurring a full-screen buffer. */
@Composable
private fun MemoryBackdrop(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context).data(url).size(12).allowHardware(false).crossfade(false).build()
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        val sx = maxWidth / 48.dp
        val sy = maxHeight / 48.dp
        AsyncImage(request, null, Modifier.align(Alignment.Center).size(48.dp)
            .graphicsLayer { scaleX = sx; scaleY = sy }
            .blur(25.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle),
            contentScale = ContentScale.FillBounds)
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.80f)))
    }
}
