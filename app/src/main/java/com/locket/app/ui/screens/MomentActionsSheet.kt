package com.locket.app.ui.screens

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.locket.app.model.Moment
import com.locket.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private val momentMediaClient = OkHttpClient()
private data class ShareTarget(val name: String, val icon: ImageVector, val pkg: String? = null, val action: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomentActionsSheet(moment: Moment, own: Boolean, onDismiss: () -> Unit,
    onAction: (String) -> Unit, shareOnly: Boolean = false, compact: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val video = !moment.videoUrl.isNullOrBlank()
    val mime = if (video) "video/mp4" else "image/jpeg"
    val filename = "Locket_${moment.timestampSeconds}.${if (video) "mp4" else "jpg"}"
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val file = pending
        pending = null
        if (uri != null && file != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("Không mở được tệp đích")
                }
                Toast.makeText(context, "Đã lưu", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { error = e.localizedMessage }
            finally { file.delete() }
        } else file?.delete()
    }
    fun execute(target: ShareTarget) {
        if (busy) return
        if (target.action in listOf("hide", "report", "block", "delete")) { onAction(target.action); return }
        busy = true; error = null
        scope.launch {
            try {
                val file = downloadMoment(context, moment, filename)
                if (target.action == "save") {
                    if (Build.VERSION.SDK_INT >= 29) {
                        saveMomentToGallery(context, file, mime, filename, video)
                        file.delete()
                        Toast.makeText(context, if (video) "Đã lưu video" else "Đã lưu ảnh", Toast.LENGTH_SHORT).show()
                    } else {
                        pending = file
                        documentPicker.launch(filename)
                    }
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                    val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = ClipData.newUri(context.contentResolver, filename, uri); target.pkg?.let { setPackage(it) } }
                    if (target.pkg == null) context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                    else try { context.startActivity(intent) }
                    catch (_: android.content.ActivityNotFoundException) {
                        intent.setPackage(null); context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                    }
                    onDismiss()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.localizedMessage ?: "Không xử lý được khoảnh khắc" }
            finally { busy = false }
        }
    }
    val targets = listOf(
        ShareTarget("Messenger", Icons.Default.ChatBubble, "com.facebook.orca", "share"),
        ShareTarget("Zalo", Icons.Default.Chat, "com.zing.zalo", "share"),
        ShareTarget("Instagram", Icons.Default.PhotoCamera, "com.instagram.android", "share"),
        ShareTarget("Messages", Icons.Default.Message, "com.google.android.apps.messaging", "share"),
        ShareTarget(if (video) "Lưu video" else "Lưu ảnh", Icons.Default.Download, action = "save"),
        ShareTarget("Ứng dụng khác", Icons.Default.Share, action = "share")
    ) + if (shareOnly) emptyList() else if (own) listOf(
        ShareTarget("Xóa khoảnh khắc", Icons.Default.Delete, action = "delete")
    ) else listOf(
        ShareTarget("Ẩn bạn này", Icons.Default.VisibilityOff, action = "hide"),
        ShareTarget("Báo cáo", Icons.Default.Flag, action = "report"),
        ShareTarget("Chặn", Icons.Default.Block, action = "block")
    )
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF262626), shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
        dragHandle = if (compact) null else ({ BottomSheetDefaults.DragHandle() })) {
        if (compact) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp), color = LocketYellow)
                error?.let { Text(it, color = Color(0xFFFF8888), modifier = Modifier.padding(12.dp)) }
                val actions = listOf(
                    ShareTarget("Chia sẻ", Icons.Default.Share, action = "share"),
                    ShareTarget("Tải về", Icons.Default.Download, action = "save")
                ) + if (own && !shareOnly) listOf(
                    ShareTarget(if (video) "Xóa video" else "Xóa ảnh", Icons.Default.Delete, action = "delete")
                ) else emptyList()
                actions.forEach { target ->
                    Box(Modifier.fillMaxWidth().height(56.dp).clickable(enabled = !busy) { execute(target) },
                        contentAlignment = Alignment.Center) {
                        Text(target.name, color = if (target.action == "delete") Color(0xFFFF7777) else Color.White,
                            fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(56.dp).clickable(enabled = !busy, onClick = onDismiss),
                    contentAlignment = Alignment.Center) {
                    Text("Hủy", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
            }
        } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (shareOnly) "Chia sẻ với bạn bè!" else "Tùy chọn khoảnh khắc", color = Color.White,
                style = profileTextStyle(19.sp, 24.sp, FontWeight.Bold))
            Spacer(Modifier.height(26.dp))
            AsyncImage(moment.thumbnailUrl, "Khoảnh khắc", Modifier.size(170.dp).rotate(-5f).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.height(28.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp), color = LocketYellow)
            error?.let { Text(it, color = Color(0xFFFF8888), modifier = Modifier.padding(bottom = 12.dp)) }
            targets.chunked(3).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    group.forEach { target ->
                        Column(Modifier.weight(1f).clickable(enabled = !busy) { execute(target) }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            val appIcon = remember(target.pkg) {
                                target.pkg?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(64,64).asImageBitmap() }.getOrNull() }
                            }
                            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF414141)), contentAlignment = Alignment.Center) {
                                if (appIcon != null) Image(appIcon, null, Modifier.size(42.dp))
                                else Icon(target.icon, null, tint = if (target.action in listOf("report", "block", "delete")) Color(0xFFFF9D45) else Color.White, modifier = Modifier.size(27.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(target.name, color = Color.White, textAlign = TextAlign.Center,
                                style = profileTextStyle(13.sp, 16.sp, FontWeight.SemiBold))
                        }
                    }
                    repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private suspend fun downloadMoment(context: Context, moment: Moment, name: String): File = withContext(Dispatchers.IO) {
    val folder = File(context.cacheDir, "moment_share").apply { mkdirs() }
    folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000L }?.forEach { it.delete() }
    val file = File.createTempFile("moment_", "_$name", folder)
    try {
        val url = moment.videoUrl?.takeIf { it.isNotBlank() } ?: moment.thumbnailUrl
        if (url.startsWith("content:") || url.startsWith("file:")) {
            context.contentResolver.openInputStream(Uri.parse(url))?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("Không đọc được ảnh")
        } else {
            momentMediaClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Tải thất bại (HTTP ${response.code})" }
                val body = response.body ?: error("Không có dữ liệu")
                body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
        check(file.length() > 0) { "Tệp trống" }
        file
    } catch (e: Exception) { file.delete(); throw e }
}

private suspend fun saveMomentToGallery(context: Context, file: File, mime: String, name: String, video: Boolean) =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Locket")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Không tạo được tệp")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Không ghi được tệp")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
