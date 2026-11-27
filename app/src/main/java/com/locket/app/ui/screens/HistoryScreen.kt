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
    onReact: (String) -> Unit
) {
    val dateStr = remember(moment.timestampSeconds) {
        val sdf = SimpleDateFormat("HH:mm • dd/MM/yyyy", Locale.getDefault())
        sdf.format(Date(moment.timestampSeconds * 1000))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(42.dp)
                            .liquidGlassEffect(CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Đóng", tint = Color.White)
                    }

                    // User Info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2C2C2E)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (moment.userAvatarUrl.isNotBlank()) {
                                AsyncImage(
                                    model = moment.userAvatarUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                        Column {
                            Text(moment.userName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(dateStr, color = LocketTextDim, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.size(42.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Photo Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.0f)
                        .clip(RoundedCornerShape(36.dp))
                        .background(Color(0xFF1E1E20))
                ) {
                    AsyncImage(
                        model = moment.thumbnailUrl,
                        contentDescription = moment.caption,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // Caption inside photo
                    if (moment.caption.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                                .liquidGlassEffect(RoundedCornerShape(20.dp), backgroundColor = Color(0xB31E1E20))
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = emojiStyled(moment.caption),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Quick Reactions Row
                Row(
                    modifier = Modifier
                        .liquidGlassEffect(RoundedCornerShape(28.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("🔥", "😍", "💛", "😂", "🥺").forEach { emoji ->
                        Text(
                            text = emojiStyled(emoji),
                            fontSize = 28.sp,
                            modifier = Modifier
                                .clickable {
                                    onReact(emoji)
                                }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}
