package com.locket.app.ui.screens

import com.locket.app.ui.theme.*

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.R
import com.locket.app.ui.LocketViewModel
import com.locket.app.widget.LocketWidgetUpdater

internal object AppearancePrefs {
    val icons = intArrayOf(R.mipmap.ic_launcher, R.drawable.custom_icon_1, R.drawable.custom_icon_2, R.drawable.custom_icon_3, R.drawable.custom_icon_4, R.drawable.custom_icon_5, R.drawable.custom_icon_6, R.drawable.custom_icon_7, R.drawable.custom_icon_8, R.drawable.custom_icon_9, R.drawable.custom_icon_10, R.drawable.custom_icon_11, R.drawable.custom_icon_12, R.drawable.custom_icon_13, R.drawable.custom_icon_14, R.drawable.custom_icon_15, R.drawable.custom_icon_16, R.drawable.custom_icon_17, R.drawable.custom_icon_18, R.drawable.custom_icon_19, R.drawable.custom_icon_20, R.drawable.custom_icon_21, R.drawable.custom_icon_22, R.drawable.custom_icon_23, R.drawable.custom_icon_24, R.drawable.custom_icon_25, R.drawable.custom_icon_26, R.drawable.custom_icon_27, R.drawable.custom_icon_28, R.drawable.custom_icon_29, R.drawable.custom_icon_30, R.drawable.custom_icon_31, R.drawable.custom_icon_32, R.drawable.custom_icon_33, R.drawable.custom_icon_34, R.drawable.custom_icon_35)
    val frames = intArrayOf(0, R.drawable.widget_frame_1, R.drawable.widget_frame_2, R.drawable.widget_frame_3, R.drawable.widget_frame_4, R.drawable.widget_frame_5, R.drawable.widget_frame_6, R.drawable.widget_frame_7, R.drawable.widget_frame_8, R.drawable.widget_frame_9, R.drawable.widget_frame_10, R.drawable.widget_frame_11)
    val themes = listOf("Mặc định", "Vàng", "Hồng", "Xanh dương", "Tím", "Xanh lá")
    private fun prefs(c: Context) = c.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun icon(c: Context) = prefs(c).getInt("icon", 0).coerceIn(0, icons.lastIndex)
    fun frame(c: Context) = prefs(c).getInt("frame", 0).coerceIn(0, frames.lastIndex)
    fun frameResource(c: Context) = frames[frame(c)]
    fun cameraTheme(c: Context) = prefs(c).getInt("camera", 0).coerceIn(0, themes.lastIndex)
    fun badge(c: Context) = prefs(c).getBoolean("badge", true)
    fun setBadge(c: Context, v: Boolean) { prefs(c).edit().putBoolean("badge", v).apply() }
    fun setFrame(c: Context, v: Int) { prefs(c).edit().putInt("frame", v).apply(); LocketWidgetUpdater.updateAll(c) }
    fun setCameraTheme(c: Context, v: Int) { prefs(c).edit().putInt("camera", v).apply() }
    fun cameraBrush(index: Int): Brush = Brush.verticalGradient(when(index) {
        1 -> listOf(Color(0xFF100E05), Color(0xFF594300))
        2 -> listOf(Color(0xFFDA075C), Color(0xFFFF491F))
        3 -> listOf(Color(0xFF061C3F), Color(0xFF167DC5))
        4 -> listOf(Color(0xFF25113C), Color(0xFF781BB0))
        5 -> listOf(Color(0xFF072E22), Color(0xFF13734C))
        else -> listOf(LocketBlack, LocketBlack)
    })
    fun setIcon(c: Context, index: Int) {
        require(index in icons.indices)
        val pm = c.packageManager
        fun component(i: Int) = ComponentName(c.packageName, c.packageName + ".LauncherIcon" + i)
        // Enable first so a launcher target always exists. The real activity stays enabled.
        pm.setComponentEnabledSetting(component(index), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        icons.indices.filter { it != index }.forEach {
            pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        prefs(c).edit().putInt("icon", index).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSheet(page: String, viewModel: LocketViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (page == "subscription") {
        FreeFeaturesScreen(onDismiss)
        return
    }
    val moments by viewModel.moments.collectAsState()
    val photo = moments.firstOrNull()
    var icon by remember { mutableIntStateOf(AppearancePrefs.icon(context)) }
    var frame by remember { mutableIntStateOf(AppearancePrefs.frame(context)) }
    var theme by remember { mutableIntStateOf(AppearancePrefs.cameraTheme(context)) }
    val pager = rememberPagerState(initialPage = theme) { AppearancePrefs.themes.size }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocketSheetBg, shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            when (page) {
                "icon" -> {
                    AppearanceTitle("Choose app icon", "Chọn một biểu tượng ứng dụng tùy chỉnh cho Màn hình chính của bạn")
                    LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(AppearancePrefs.icons.size) { i ->
                            Column(Modifier.clickable {
                                runCatching { AppearancePrefs.setIcon(context, i) }
                                    .onSuccess { icon = i }
                                    .onFailure { Toast.makeText(context, "Không đổi được icon", Toast.LENGTH_SHORT).show() }
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                                val previewModifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(15.dp))
                                if (i == 0) {
                                    val bitmap = remember(context) { context.getDrawable(AppearancePrefs.icons[0])?.toBitmap(144, 144)?.asImageBitmap() }
                                    if (bitmap != null) Image(bitmap, "Biểu tượng mặc định", previewModifier)
                                    else Box(previewModifier.background(LocketYellow))
                                } else Image(painterResource(AppearancePrefs.icons[i]), "Biểu tượng ${i + 1}", previewModifier)
                                Spacer(Modifier.height(8.dp)); AppearanceCheck(i == icon)
                            }
                        }
                    }
                }
                "widget" -> {
                    Box(Modifier.padding(top = 12.dp).size(146.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF303030))) {
                        if (photo != null && photo.thumbnailUrl.isNotBlank()) AsyncImage(photo.thumbnailUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (frame != 0) Image(painterResource(AppearancePrefs.frames[frame]), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                    Text("Mọi người", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(AppearancePrefs.frames.size) { i ->
                            Column(Modifier.clickable { frame = i; AppearancePrefs.setFrame(context, i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(LocketSheetSurface), contentAlignment = Alignment.Center) {
                                    if (i == 0) Text("Không có", color = Color.White, fontWeight = FontWeight.Bold)
                                    else Image(painterResource(AppearancePrefs.frames[i]), "Khung ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                                }
                                Spacer(Modifier.height(8.dp)); AppearanceCheck(frame == i)
                            }
                        }
                    }
                }
                "camera" -> {
                    AppearanceTitle("Choose camera theme", "Chọn một chủ đề để trang trí máy ảnh của bạn")
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val width = maxWidth * 0.64f
                        val height = minOf(maxHeight - 24.dp, width * 2.05f)
                        HorizontalPager(state = pager, pageSize = PageSize.Fixed(width), contentPadding = PaddingValues(horizontal = (maxWidth - width) / 2), pageSpacing = 24.dp) { i ->
                            Column(Modifier.width(width).height(height).clip(RoundedCornerShape(34.dp)).background(AppearancePrefs.cameraBrush(i)),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Box(Modifier.size(28.dp).background(Color.White.copy(alpha = 0.23f), CircleShape))
                                    Box(Modifier.size(72.dp, 28.dp).background(Color.White.copy(alpha = 0.23f), CircleShape))
                                    Box(Modifier.size(28.dp).background(Color.White.copy(alpha = 0.23f), CircleShape))
                                }
                                Box(Modifier.fillMaxWidth().height(width.coerceAtMost(height * 0.46f)).clip(RoundedCornerShape(24.dp)).background(Color(0xFF303030))) {
                                    if (photo != null && photo.thumbnailUrl.isNotBlank()) AsyncImage(photo.thumbnailUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Box(Modifier.size(56.dp).border(3.dp, LocketYellow, CircleShape).padding(5.dp).background(Color.White, CircleShape))
                                Text(AppearancePrefs.themes[i], color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Button(onClick = { theme = pager.settledPage; AppearancePrefs.setCameraTheme(context, theme) }, modifier = Modifier.fillMaxWidth().padding(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LocketYellow, contentColor = LocketOnYellow)) {
                        Text(if (theme == pager.settledPage) "Đang sử dụng" else "Áp dụng")
                    }
                }

            }
        }
    }
}
@Composable
private fun AppearanceTitle(title: String, subtitle: String) {
    Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    Text(subtitle, color = Color.White.copy(alpha = 0.45f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 38.dp, vertical = 12.dp))
}
@Composable
private fun AppearanceCheck(selected: Boolean) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(if (selected) LocketYellow else Color.Transparent)
        .border(2.dp, if (selected) LocketYellow else Color(0xFF555555), CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Default.Check, "Đang sử dụng", tint = Color.Black, modifier = Modifier.size(20.dp))
    }
}


@Composable
private fun FreeFeaturesScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Repository created_at: 2026-11-27T09:10:35Z (GitHub).
    val features = listOf(
        Triple("🚫", "Không có quảng cáo!", "Không chèn quảng cáo trong ứng dụng"),
        Triple("🎥", "Video Lockets", "Ghi lại các video clip ngắn!"),
        Triple("👀", "Lượt xem Locket", "Xem hoạt động trên ảnh của bạn khi máy chủ cung cấp dữ liệu"),
        Triple("🖼️", "Tải lên từ cuộn camera", "Chia sẻ các kỷ niệm từ thư viện của bạn"),
        Triple("❤️‍🔥", "Thêm bạn bè", "Kết bạn theo giới hạn của máy chủ Locket"),
        Triple("💛", "Biểu tượng ứng dụng tùy chỉnh", "Trang trí Màn hình chính của bạn"),
        Triple("💬", "Gửi phụ đề", "Thêm nội dung trên các ảnh của bạn"),
        Triple("🎨", "Khung widget", "Trang trí widget của bạn"),
        Triple("🖌️", "Chủ đề máy ảnh", "Tùy chỉnh màu sắc giao diện camera")
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050403), Color(0xFF181306), Color(0xFF4A3504))))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Default.Close, "Đóng", tint = Color.White.copy(alpha = 0.65f))
                        }
                        Box(Modifier.align(Alignment.Center).padding(top = 18.dp).size(76.dp)
                            .clip(RoundedCornerShape(22.dp)).background(LocketGoldBanner)
                            .border(4.dp, Color(0xFF494125), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Favorite, null, tint = LocketGold, modifier = Modifier.size(48.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Locket Gold", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = LocketGoldHighlightStart)
                    Text("Miễn phí", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = LocketGold,
                        modifier = Modifier.padding(top = 10.dp))
                    Text("Dự án ra đời ngày 27/11/2026", color = Color(0xFFC8B98B), fontSize = 15.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 36.dp))
                    features.forEachIndexed { i, (emoji, title, description) ->
                        val shape = RoundedCornerShape(
                            topStart = if (i == 0) 24.dp else 5.dp,
                            topEnd = if (i == 0) 24.dp else 5.dp,
                            bottomStart = if (i == features.lastIndex) 24.dp else 5.dp,
                            bottomEnd = if (i == features.lastIndex) 24.dp else 5.dp)
                        Row(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.09f))
                            .padding(horizontal = 16.dp, vertical = 11.dp).heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                            Text(emoji, fontSize = 32.sp, modifier = Modifier.width(38.dp), textAlign = TextAlign.Center)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF3EFE6))
                                Text(description, fontSize = 13.sp, color = Color(0xFFB6AD98))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Text("Và nhiều tính năng khác nữa...", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFC8B98B), modifier = Modifier.padding(vertical = 18.dp))
                    Text("Bản tùy chỉnh miễn phí; không phải gói thuê bao Gold chính thức.", color = Color(0xFFB6AD98),
                        fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp))
                }
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ngocminhvn/locket/issues/new"))) }
                        .onFailure { Toast.makeText(context, "Không mở được GitHub", Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LocketGold, contentColor = LocketOnYellow),
                    shape = CircleShape) {
                    Text("Yêu cầu một tính năng", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ngocminhvn/locket"))) }
                        .onFailure { Toast.makeText(context, "Không mở được GitHub", Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("GitHub · Miễn phí", color = Color(0xFFC8B98B), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
