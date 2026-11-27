package com.locket.app.ui.screens

import androidx.compose.foundation.background
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
    val moments by viewModel.moments.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val historyLoading by viewModel.historyLoading.collectAsState()
    val historyHasMore by viewModel.historyHasMore.collectAsState()
    val historyError by viewModel.historyError.collectAsState()
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

    // Generate list of months from current month going back 14 months
    val calendarMonths = remember {
        val list = mutableListOf<CalendarMonthData>()
        val cal = Calendar.getInstance()
        // Ensure starting from current month
        for (i in 0 until 15) {
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            list.add(
                CalendarMonthData(
                    year = y,
                    month = m,
                    monthTitle = "Tháng $m $y",
                    daysCount = daysInMonth
                )
            )
            cal.add(Calendar.MONTH, -1)
        }
        list
    }

    val totalCount = moments.size
    val streak = remember(moments) { viewModel.calculateStreak(moments) }

    // Lich su truoc day chi duoc tai khi vuot o HomePager / 1 lan trong
    // syncAllData, nen mo thang man Ky niem co the chua co du lieu cac thang
    // cu (chi hien cham tron du user co moment o thang do). Man nay tu tai
    // them cac dot lich su cho den khi du phu 15 thang dang hien thi hoac
    // het du lieu. Lich su sap xep moi-nhat-truoc nen khi moment cu nhat
    // da tai ve cu hon dau thang cu nhat dang hien thi la du.
    val earliestNeededTs = remember(calendarMonths) {
        val oldest = calendarMonths.last()
        Calendar.getInstance().apply {
            set(Calendar.YEAR, oldest.year)
            set(Calendar.MONTH, oldest.month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
    }
    val oldestLoadedTs = remember(moments) { moments.minOfOrNull { it.timestampSeconds } }
    val allMonthsCovered = oldestLoadedTs != null && oldestLoadedTs < earliestNeededTs
    val needsMoreHistory = viewModel.isLoggedIn && historyHasMore &&
        historyError == null && !allMonthsCovered

    LaunchedEffect(needsMoreHistory, historyLoading, historyError, moments.size) {
        if (needsMoreHistory && !historyLoading) {
            viewModel.loadOlderMoments()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Header: "Kỷ niệm" + Avatar (Matches 01-history-ky-niem.jpg)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.size(40.dp))

                    Text(
                        text = "Kỷ niệm",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Avatar
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2C2C2E))
                            .border(1.5.dp, LocketYellow, CircleShape)
                            .clickable(onClick = onOpenProfile),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentUser.profilePictureUrl.isNotBlank()) {
                            AsyncImage(
                                model = currentUser.profilePictureUrl,
                                contentDescription = "Profile",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Month Cards
            items(calendarMonths) { monthData ->
                // Thang chua du du lieu khi lich su moi tai toi trong (hoac
                // sau) dau thang do ma van con dot cu hon chua tai ve.
                val monthStartTs = Calendar.getInstance().apply {
                    set(Calendar.YEAR, monthData.year)
                    set(Calendar.MONTH, monthData.month - 1)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis / 1000
                val monthCovered = oldestLoadedTs != null && oldestLoadedTs < monthStartTs
                val monthLoading = viewModel.isLoggedIn && historyHasMore &&
                    historyError == null && !monthCovered
                MonthCardDynamic(
                    monthData = monthData,
                    momentsByDate = momentsByDate,
                    isLoading = monthLoading,
                    onMomentClick = { viewingMoment = it },
                    onTodayAddClick = { onNavigate("camera") }
                )

                // Dashed Divider between months
                Text(
                    text = "- - - - - -",
                    color = Color(0xFF333333),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            // Trang thai tai lich su: dang tai thi hien spinner, loi thi hien
            // nut thu lai. Khong de thang cu chi hien cham tron gay hieu
            // nham la thang do khong co anh.
            if (historyLoading || historyError != null) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        if (historyLoading) {
                            CircularProgressIndicator(color = LocketYellow)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "Đang tải ảnh cũ...", color = LocketTextDim, fontSize = 13.sp)
                        }
                        if (historyError != null) {
                            Text(
                                text = historyError ?: "",
                                color = Color(0xFFFF6B6B),
                                fontSize = 13.sp
                            )
                            TextButton(onClick = { viewModel.loadOlderMoments() }) {
                                Text("Thử lại", color = LocketYellow)
                            }
                        }
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
                        text = "💛 $totalCount Locket",
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
                        text = "🔥 $streak ngày chuỗi",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
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
    onMomentClick: (Moment) -> Unit,
    onTodayAddClick: () -> Unit
) {
    val todayCal = Calendar.getInstance()
    val isCurrentMonth = todayCal.get(Calendar.YEAR) == monthData.year &&
            (todayCal.get(Calendar.MONTH) + 1) == monthData.month
    val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF141416))
            .padding(16.dp)
    ) {
        Text(
            text = monthData.monthTitle,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Thang chua tai du du lieu: bao dang tai thay vi de cham tron
        // (cham tron chi co nghia "khong co anh" khi da tai het).
        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                CircularProgressIndicator(
                    color = LocketYellow,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Đang tải ảnh cũ...",
                    color = LocketTextDim,
                    fontSize = 12.sp
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in 0 until 5) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (col in 0 until 7) {
                        val dayIndex = row * 7 + col + 1
                        val key = "${monthData.year}-${monthData.month}-$dayIndex"
                        val dayMoments = momentsByDate[key]
                        val hasMoments = !dayMoments.isNullOrEmpty()
                        val isToday = isCurrentMonth && dayIndex == todayDay

                        val specialEmoji = when {
                            monthData.month == 10 && dayIndex == 31 -> "🎃"
                            monthData.month == 12 && dayIndex == 25 -> "🎁"
                            monthData.month == 12 && dayIndex == 24 -> "🎄"
                            monthData.month == 1 && dayIndex == 1 -> "🎆"
                            monthData.month == 2 && dayIndex == 14 -> "💝"
                            else -> null
                        }

                        Box(
                            modifier = Modifier.size(42.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                hasMoments -> {
                                    val firstM = dayMoments!!.first()
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF2C2C2E))
                                            .clickable { onMomentClick(firstM) }
                                    ) {
                                        AsyncImage(
                                            model = firstM.thumbnailUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                                isToday -> {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .border(2.5.dp, LocketYellow, RoundedCornerShape(12.dp))
                                            .clickable(onClick = onTodayAddClick),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Thêm khoảnh khắc",
                                            tint = LocketYellow,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                specialEmoji != null && dayIndex <= monthData.daysCount -> {
                                    Text(text = specialEmoji, fontSize = 24.sp)
                                }
                                dayIndex <= monthData.daysCount -> {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .background(Color(0xFF2C2C2E), CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
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
                                text = moment.caption,
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
                            text = emoji,
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
