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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
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
import com.locket.app.ui.MomentVideo
import com.locket.app.ui.components.liquidGlassEffect
import com.locket.app.ui.components.LocketProfileAvatar
import com.locket.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
    viewModel: LocketViewModel,
    onNavigate: (String) -> Unit,
    onOpenProfile: () -> Unit,
    sharedProfileAvatar: Boolean = false
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
    // Select a real thumbnail belonging to the visible calendar month, never the feed.
    val historyUrl = remember(oldestVisibleMonth, momentsByDate) {
        (oldestVisibleMonth.daysCount downTo 1).firstNotNullOfOrNull { day ->
            momentsByDate["${oldestVisibleMonth.year}-${oldestVisibleMonth.month}-$day"]
                ?.firstOrNull()?.thumbnailUrl?.takeIf { it.isNotBlank() }
        }
    }
    val ambient = rememberAmbientPalette(historyUrl, AmbientMode.HISTORY)
    val topRampColor = ambient.top
    val bottomRampColor = ambient.bottom
    val cellPx = (context.resources.displayMetrics.widthPixels / 8).coerceAtLeast(1)
    LaunchedEffect(oldestVisibleMonth, momentsByDate) {
        val index = calendarMonths.indexOf(oldestVisibleMonth)
        val months = calendarMonths.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(calendarMonths.size))
        val urls = months.flatMap { month -> (1..month.daysCount).mapNotNull { day -> momentsByDate["${month.year}-${month.month}-$day"]?.firstOrNull()?.thumbnailUrl } }
        viewModel.prefetchThumbnails(urls, cellPx)
    }
    val neededTs = remember(oldestVisibleMonth) { monthStart(oldestVisibleMonth) }
    val needsMore = viewModel.isLoggedIn && historyHasMore && historyError == null &&
        (coveredThrough == null || coveredThrough!! >= neededTs)
    LaunchedEffect(needsMore, historyLoading, historyError, coveredThrough) {
        if (needsMore && !historyLoading) viewModel.loadOwnHistory()
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ambient.brush())
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 96.dp, bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
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
                        Text(historyError.orEmpty(), color = LocketRed, fontSize = 13.sp)
                        TextButton(onClick = { viewModel.loadOwnHistory() }) { Text("Thử lại", color = LocketYellow) }
                    }
                }
            }

            // Stats Pill: "💛 {totalCount} Locket | 🔥 {streak} ngày chuỗi"
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .liquidGlassEffect(RoundedCornerShape(24.dp), backgroundColor = LocketPillBg)
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
            .background(Brush.verticalGradient(listOf(topRampColor, topRampColor.copy(alpha = 0.88f), Color.Transparent)))
            .statusBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp).height(46.dp)) {
            if (!sharedProfileAvatar) Text("Kỷ niệm", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center))
            if (!sharedProfileAvatar) LocketProfileAvatar(currentUser.profilePictureUrl, onOpenProfile,
                Modifier.align(Alignment.CenterEnd))
        }

        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .height(88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            .background(Brush.verticalGradient(listOf(Color.Transparent, bottomRampColor))))

        // Memory Full-Screen Viewer Dialog
        viewingMoment?.let { moment ->
            MemoryDetailDialog(
                viewModel = viewModel,
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
                onPrefetch = { items, index, size -> viewModel.prefetchMemoryMoments(items, index, size) },
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
    val cardColor = LocketSheetBg
    val headerColor = LocketSheetBg
    val dayColor = LocketJournalDayBg
    val dotColor = LocketSheetSurface
    val todayCal = Calendar.getInstance()
    val isCurrentMonth = todayCal.get(Calendar.YEAR) == monthData.year &&
            (todayCal.get(Calendar.MONTH) + 1) == monthData.month
    val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)

    val firstDate = java.time.LocalDate.of(monthData.year, monthData.month, 1)
    val offset = firstDate.dayOfWeek.value - 1
    val rows = (offset + monthData.daysCount + 6) / 7
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(24.dp)).background(cardColor)
    ) {
        Text(monthData.monthTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().background(headerColor).padding(horizontal = 16.dp, vertical = 12.dp))
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
                                ownMoment != null -> AsyncImage(ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                    .data(ownMoment.thumbnailUrl).size((androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels / 8).coerceAtLeast(1)).build(), null,
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)).background(dayColor)
                                        .clickable { onMomentClick(ownMoment) }, contentScale = ContentScale.Crop,
                                    placeholder = androidx.compose.ui.graphics.painter.ColorPainter(dotColor))
                                isLoading && !future -> {
                                    val phase = (shimmer * 2f - (row * 7 + col) / 35f).coerceIn(0f, 1f)
                                    val intensity = (1f - kotlin.math.abs(phase * 2f - 1f)) * 0.55f
                                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                        .background(androidx.compose.ui.graphics.lerp(dayColor, dotColor, intensity)))
                                }
                                today -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                    .background(LocketGoldBanner).border(2.5.dp, LocketYellow, RoundedCornerShape(10.dp))
                                    .clickable(onClick = onTodayAddClick), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, "Thêm khoảnh khắc", tint = LocketYellow, modifier = Modifier.size(24.dp))
                                }
                                future -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(dayColor))
                                holiday != null -> Text(holiday, fontSize = 20.sp)
                                else -> Box(Modifier.size(11.dp).background(dotColor, CircleShape))
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
    val connectorColor = Color(0xFF3A3A3A)
    Canvas(Modifier.padding(vertical = 8.dp).size(width = 46.dp, height = 16.dp)) {
        val line = Path().apply {
            moveTo(0f, size.height * 0.15f)
            cubicTo(size.width * 0.25f, size.height, size.width * 0.75f, 0f, size.width, size.height * 0.85f)
        }
        drawPath(line, connectorColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))))
    }
}

@Composable
fun MemoryDetailDialog(
    viewModel: LocketViewModel,
    moment: Moment,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    historyMoments: List<Moment> = emptyList(),
    hasMore: Boolean = false,
    loadingOlder: Boolean = false,
    historyError: String? = null,
    onLoadOlder: () -> Unit = {},
    onPrefetch: (List<Moment>, Int, Int) -> Unit = { _, _, _ -> },
    onDelete: ((Moment, Boolean, () -> Unit) -> Unit)? = null
) {
    val photos = remember(moment.id, historyMoments) {
        (historyMoments + moment).distinctBy { it.id }.sortedByDescending { it.timestampSeconds }
    }
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.id == moment.id }.coerceAtLeast(0)) { photos.size }
    val scope = rememberCoroutineScope()
    val active = photos[pager.settledPage.coerceIn(0, photos.lastIndex)]
    val showViewCount by viewModel.showViewCount.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val canShowActivity = showViewCount && currentUser.uid.isNotBlank() && active.userUid == currentUser.uid
    // Request just the settled moment; the existing Home activity component
    // reuses the same per-moment viewer cache and refreshes on opening.
    LaunchedEffect(active.id, canShowActivity) {
        if (canShowActivity && viewModel.momentViewers.value[active.id] == null) {
            viewModel.refreshMomentViews(active)
        }
    }
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
    var thumbDragging by remember { mutableStateOf(false) }
    val moving = pager.isScrollInProgress || thumbDragging || kotlin.math.abs(pager.currentPageOffsetFraction) > 0.001f
    LaunchedEffect(active.id) {
        zoom = 1f
        pan = Offset.Zero
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
                // Reserve space for the activity chip above the filmstrip on compact displays.
                val activityLimit = if (canShowActivity) (maxHeight - 320.dp).coerceAtLeast(1.dp) else maxHeight
                val photoSize = minOf(viewportWidth - 48.dp, maxHeight * 0.48f, activityLimit).coerceAtLeast(1.dp)
                val imagePx = with(androidx.compose.ui.platform.LocalDensity.current) { photoSize.roundToPx().coerceAtLeast(1) }
                val thumbnailPx = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.roundToPx() }
                val imageContext = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(active.id, photos.size, imagePx) { onPrefetch(photos, pager.settledPage, imagePx) }
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
                    IconButton(onClick = { share = true }, enabled = !moving, modifier = Modifier.align(Alignment.CenterEnd).size(44.dp)) {
                        Icon(Icons.Default.Share, "Chia sẻ", tint = Color.White.copy(alpha = 0.90f), modifier = Modifier.size(26.dp))
                    }
                }

                Column(Modifier.align(Alignment.Center).offset(y = (if (canShowActivity) -3 else -31).dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(photoSize),
                        pageSize = PageSize.Fixed(photoSize),
                        contentPadding = PaddingValues(horizontal = (viewportWidth - photoSize) / 2),
                        pageSpacing = 16.dp,
                        beyondViewportPageCount = 1,
                        key = { photos[it].id },
                        userScrollEnabled = zoom <= 1f && !share && !delete && !thumbDragging) { page ->
                        val photo = photos[page]
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(photoSize).graphicsLayer {
                                val distance = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                                scaleY = 1f - 0.28f * distance
                            }.clip(RoundedCornerShape(46.dp))
                                .background(Color(0xFF1E1E20))) {
                                val selected = page == pager.settledPage
                                AsyncImage(remember(photo.thumbnailUrl, imagePx) { ImageRequest.Builder(imageContext).data(photo.thumbnailUrl).size(imagePx).build() }, photo.caption,
                                    Modifier.fillMaxSize()
                                        .then(if (selected && photo.videoUrl.isNullOrBlank()) Modifier.pointerInput(photo.id) {
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
                                    contentScale = ContentScale.Crop,
                                    placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFF303032)))
                                if (selected && !moving && !photo.videoUrl.isNullOrBlank()) {
                                    MomentVideo(photo.videoUrl, Modifier.fillMaxSize())
                                }
                                if (!moving && (zoom <= 1f || !selected)) {
                                    val photoTime = remember(photo.timestampSeconds) {
                                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(photo.timestampSeconds * 1000))
                                    }
                                    if (com.locket.app.model.CaptionOverlays.data(photo) != null || photo.caption.isNotBlank()) {
                                        MomentCaption(photo, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 20.dp))
                                    } else {
                                        Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 24.dp)
                                            .background(Color.Black.copy(alpha = 0.44f), CircleShape)
                                            .padding(horizontal = 13.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Default.AccessTime, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                                            Text(photoTime, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        }
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
                    if (canShowActivity) {
                        // Keep the slot fixed during swipes and zooms; never show another photo's viewers.
                        if (!moving && zoom <= 1f && !share && !delete) {
                            key(active.id) { OwnMomentActivityBar(viewModel, active) }
                        } else Spacer(Modifier.height(56.dp))
                    }
                }

                if (historyError != null && pager.currentPage >= (photos.size - 3).coerceAtLeast(0)) {
                    TextButton(onClick = onLoadOlder, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 86.dp)) { Text("Thử tải ảnh cũ", color = Color.White) }
                }
                // One scroll position drives both carousels. The outline is
                // a stationary overlay, not a border attached to a moving item.
                val slotWidth = viewportWidth / 5
                val dragRatio = (photoSize + 16.dp) / slotWidth
                val dragState = rememberDraggableState { delta ->
                    pager.dispatchRawDelta(-delta * dragRatio)
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 23.dp)
                    .height(60.dp).clipToBounds()
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        enabled = photos.size > 1 && zoom <= 1f && !share && !delete,
                        onDragStarted = {
                            pager.stopScroll()
                            thumbDragging = true
                        },
                        onDragStopped = { velocity ->
                            try {
                                val direction = when {
                                    velocity < -600f -> 1
                                    velocity > 600f -> -1
                                    else -> 0
                                }
                                val target = (pager.currentPage + direction).coerceIn(0, photos.lastIndex)
                                pager.animateScrollToPage(target)
                            } finally {
                                thumbDragging = false
                            }
                        }
                    ), contentAlignment = Alignment.Center) {
                    val first = (pager.currentPage - 3).coerceAtLeast(0)
                    val last = (pager.currentPage + 3).coerceAtMost(photos.lastIndex)
                    for (index in first..last) {
                        val photo = photos[index]
                        key(photo.id) {
                            val position = index - pager.currentPage - pager.currentPageOffsetFraction
                            val distance = kotlin.math.abs(position)
                            val thumbSize = when {
                                distance < 1f -> (50f - 9f * distance).dp
                                distance < 2f -> (41f - 9f * (distance - 1f)).dp
                                else -> 32.dp
                            }
                            Box(Modifier.offset(x = slotWidth * position).size(60.dp),
                                contentAlignment = Alignment.Center) {
                                AsyncImage(remember(photo.thumbnailUrl, thumbnailPx) { ImageRequest.Builder(imageContext).data(photo.thumbnailUrl).size(thumbnailPx).build() }, "Ảnh kỷ niệm",
                                    Modifier.size(thumbSize).clip(RoundedCornerShape(if (distance < 0.5f) 12.dp else 9.dp))
                                        .clickable(enabled = !share && !delete) {
                                            zoom = 1f; pan = Offset.Zero
                                            scope.launch { pager.animateScrollToPage(index) }
                                        }, contentScale = ContentScale.Crop)
                            }
                        }
                    }
                    Box(Modifier.size(60.dp)
                        .border(3.dp, Color.White.copy(alpha = 0.90f), RoundedCornerShape(18.dp)))
                }
            }
        }

        if (share) {
            MomentActionsSheet(active, own = true, onDismiss = { share = false },
                onAction = { action -> share = false; if (action == "delete") delete = true },
                shareOnly = onDelete == null, compact = true)
        }
        if (delete && onDelete != null) {
            AlertDialog(onDismissRequest = { if (!deleting) delete = false }, containerColor = LocketModalBg,
                title = { Text(if (active.videoUrl.isNullOrBlank()) "Xóa ảnh?" else "Xóa video?") },
                text = { Text("Chỉ xóa khỏi lịch sử của bạn hoặc xóa cho mọi người.") },
                confirmButton = { TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    onDelete(active, true) { deleting = false; delete = false }
                }) { Text("Xóa cho mọi người", color = LocketRed) } },
                dismissButton = { TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    onDelete(active, false) { deleting = false; delete = false }
                }) { Text("Chỉ xóa của mình") } })
        }
    }
}


@Composable
private fun MemoryBackdrop(url: String) {
    AmbientBackground(url, AmbientMode.MEMORY)
}
