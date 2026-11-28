package com.locket.app.ui.screens

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
import com.locket.app.ui.theme.LocketYellow
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
        else -> listOf(Color.Black, Color.Black)
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
    val moments by viewModel.moments.collectAsState()
    val photo = moments.firstOrNull()
    var icon by remember { mutableIntStateOf(AppearancePrefs.icon(context)) }
    var frame by remember { mutableIntStateOf(AppearancePrefs.frame(context)) }
    var theme by remember { mutableIntStateOf(AppearancePrefs.cameraTheme(context)) }
    val pager = rememberPagerState(initialPage = theme) { AppearancePrefs.themes.size }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF242424), shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp)) {
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
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2B2B2B)), contentAlignment = Alignment.Center) {
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
                        colors = ButtonDefaults.buttonColors(containerColor = LocketYellow, contentColor = Color.Black)) {
                        Text(if (theme == pager.settledPage) "Đang sử dụng" else "Áp dụng")
                    }
                }
                else -> {
                    AppearanceTitle("Locket Gold", "Quản lý gói đăng ký")
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        Text("Không có dữ liệu xác minh gói đăng ký trong ứng dụng này.", color = Color.LightGray)
                        Spacer(Modifier.height(16.dp))
                        Text("Các tùy chỉnh icon, widget và camera được lưu trên thiết bị.", color = Color.White)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"))) }
                                .onFailure { Toast.makeText(context, "Không mở được Google Play", Toast.LENGTH_SHORT).show() }
                        }) { Text("Quản lý gói đăng ký trên Google Play") }
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
