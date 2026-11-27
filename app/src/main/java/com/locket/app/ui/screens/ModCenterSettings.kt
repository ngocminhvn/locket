package com.locket.app.ui.screens

import android.content.Context
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.theme.LocketTextDim
import com.locket.app.ui.theme.LocketYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val REPO = "https://github.com/ngocminhvn/locket"
private val updateClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
private data class UpdateRelease(val version: String, val build: Long, val url: String, val size: Long, val sha256: String?)

@Suppress("DEPRECATION")
private fun installedBuild(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

private suspend fun latestRelease(): UpdateRelease? = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("https://api.github.com/repos/ngocminhvn/locket/releases/latest")
        .header("Accept", "application/vnd.github+json").header("Cache-Control", "no-cache").build()
    updateClient.newCall(req).execute().use { response ->
        if (response.code == 404) return@withContext null
        check(response.isSuccessful) { "GitHub HTTP ${response.code}" }
        val json = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
        val tag = json.get("tag_name").asString
        val version = tag.removePrefix("v")
        val match = Regex("""1\.0\.(\d+)""").matchEntire(version) ?: error("Phiên bản phát hành không hợp lệ")
        val build = match.groupValues[1].toLong()
        val name = "Locket-$version.apk"
        val asset = json.getAsJsonArray("assets").map { it.asJsonObject }
            .firstOrNull { it.get("name").asString == name } ?: error("Bản phát hành chưa có APK")
        val url = asset.get("browser_download_url").asString
        check(url.startsWith("$REPO/releases/download/$tag/")) { "Nguồn APK không hợp lệ" }
        val size = asset.get("size").asLong
        val manifestAsset = json.getAsJsonArray("assets").map { it.asJsonObject }
            .firstOrNull { it.get("name").asString == "update.json" }
        val assetDigest = asset.get("digest")?.takeUnless { it.isJsonNull }?.asString
            ?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        val sha256 = if (manifestAsset != null) {
            val manifestUrl = manifestAsset.get("browser_download_url").asString
            check(manifestUrl == "$REPO/releases/download/$tag/update.json") { "Nguồn update.json không hợp lệ" }
            updateClient.newCall(Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build())
                .execute().use { manifestResponse ->
                    check(manifestResponse.isSuccessful) { "Không đọc được update.json (HTTP ${manifestResponse.code})" }
                    val manifest = JsonParser.parseString(manifestResponse.body?.string().orEmpty()).asJsonObject
                    check(manifest.get("versionCode").asLong == build &&
                        manifest.get("versionName").asString == version &&
                        manifest.get("apkUrl").asString == url &&
                        manifest.get("sizeBytes").asLong == size) { "Thông tin bản cập nhật không khớp" }
                    val hash = manifest.get("sha256").asString
                    check(hash.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 không hợp lệ" }
                    check(assetDigest == null || assetDigest.equals(hash, ignoreCase = true)) { "SHA-256 của Release không khớp" }
                    hash
                }
        } else assetDigest // Compatibility with releases predating update.json.
        UpdateRelease(version, build, url, size, sha256)
    }
}

@Suppress("DEPRECATION")
private fun verifyUpdate(context: Context, apk: File, build: Long) {
    val pm = context.packageManager
    val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val installed = pm.getPackageInfo(context.packageName, flag)
    val archive = pm.getPackageArchiveInfo(apk.absolutePath, flag) ?: error("APK không hợp lệ")
    check(archive.packageName == context.packageName) { "Sai tên gói ứng dụng" }
    val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    check(code == build && code > installedBuild(context)) { "Sai phiên bản APK" }
    val currentCerts = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
    val newCerts = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
    // Some Android builds do not return signingInfo for an archive.
    // Missing metadata is not a mismatch. Android's package installer is
    // the final authority for signature verification, as in TKB.
    if (!currentCerts.isNullOrEmpty() && !newCerts.isNullOrEmpty()) {
        check(currentCerts.toSet() == newCerts.toSet()) {
            "Chứng chỉ ký APK khác bản đang cài. Android không cho cập nhật đè."
        }
    }
}

private suspend fun downloadUpdate(context: Context, release: UpdateRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    val partial = File(dir, "Locket-${release.version}.partial.apk")
    val apk = File(dir, "Locket-${release.version}.apk")
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        updateClient.newCall(Request.Builder().url(release.url).header("Cache-Control", "no-cache").build()).execute().use { response ->
            check(response.isSuccessful) { "Tải APK thất bại (HTTP ${response.code})" }
            val body = response.body ?: error("Không nhận được APK")
            val total = release.size.takeIf { it > 0 } ?: body.contentLength()
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var received = 0L
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        received += n
                        if (total > 0) withContext(Dispatchers.Main) { progress((received.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                    check(total <= 0 || received == total) { "APK tải chưa đủ" }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        release.sha256?.let { expected ->
            check(expected.equals(hash, ignoreCase = true)) { "SHA-256 không khớp. Đã hủy file tải." }
        }
        verifyUpdate(context, partial, release.build)
        apk.delete()
        check(partial.renameTo(apk)) { "Không lưu được APK" }
        apk
    } finally { partial.delete() }
}

private fun installUpdate(context: Context, file: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val clip = ClipData.newUri(context.contentResolver, "Locket update", uri)
    var lastError: Exception? = null
    for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE)) {
        try {
            context.startActivity(Intent(action).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                setClipData(clip)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        } catch (e: ActivityNotFoundException) { lastError = e }
        catch (e: SecurityException) { lastError = e }
    }
    throw IllegalStateException("Không mở được trình cài đặt APK", lastError)
}

@Composable
fun ModCenterSettings(viewModel: LocketViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<UpdateRelease?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }

    ModRow({ Icon(Icons.Default.Info, null, tint = Color.White) }, "Phiên bản ứng dụng", version)
    ModRow({ Icon(Icons.Default.SystemUpdate, null, tint = Color.White) }, "Kiểm tra cập nhật",
        if (busy) "Đang kiểm tra..." else null, enabled = !busy && progress == null) {
        busy = true
        scope.launch {
            try {
                val latest = latestRelease()
                if (latest == null) message = "Chưa có bản phát hành APK."
                else if (latest.build > installedBuild(context)) release = latest
                else message = "Bạn đang sử dụng phiên bản mới nhất."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.localizedMessage ?: "Không kiểm tra được cập nhật" }
            finally { busy = false }
        }
    }
    ModRow({ Icon(Icons.Default.Code, null, tint = Color.White) }, "GitHub", "github.com/ngocminhvn/locket") {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO))) }
        catch (e: Exception) { Toast.makeText(context, "Không mở được GitHub", Toast.LENGTH_SHORT).show() }
    }

    // Nut tat "Hien luot xem" (chip luot xem tren moment cua minh,
    // Batch 7a). Mac dinh bat; tat thi an chip.
    val showViewCount by viewModel.showViewCount.collectAsState()
    ProfileSettingsRow(
        icon = { Icon(Icons.Default.Visibility, null, tint = Color(0xFFCCCCCC)) },
        title = "Hiện lượt xem",
        subtitle = "Hiện số lượt xem trên khoảnh khắc của bạn",
        trailing = {
            Switch(checked = showViewCount, onCheckedChange = { viewModel.setShowViewCount(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LocketYellow))
        },
        onClick = { viewModel.setShowViewCount(!showViewCount) }
    )

    release?.let { update ->
        AlertDialog(onDismissRequest = { if (progress == null) { release = null; downloaded = null } },
            title = { Text("Có phiên bản mới") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Phiên bản ${update.version}")
                    progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Đang tải ${(p * 100).toInt()}%") }
                    if (downloaded != null) Text("APK đã tải và kiểm tra. Nếu cần, cho phép cài ứng dụng từ nguồn này rồi nhấn Cài đặt lại.")
                }
            },
            confirmButton = {
                TextButton(enabled = progress == null, onClick = {
                    val file = downloaded
                    if (file != null) {
                        try { installUpdate(context, file) } catch (e: Exception) { message = e.localizedMessage }
                    } else {
                        progress = 0f
                        scope.launch {
                            try {
                                val apk = downloadUpdate(context, update) { progress = it }
                                downloaded = apk
                                installUpdate(context, apk)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { release = null; message = e.localizedMessage ?: "Không tải được bản cập nhật" }
                            finally { progress = null }
                        }
                    }
                }) { Text(if (downloaded != null) "Cài đặt" else "Tải và cài đặt", color = LocketYellow) }
            },
            dismissButton = { TextButton(enabled = progress == null, onClick = { release = null; downloaded = null }) { Text("Đóng") } }
        )
    }
    message?.let { value ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("Cập nhật") },
            text = { Text(value) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
}

@Composable
private fun ModRow(icon: @Composable () -> Unit, title: String, subtitle: String? = null,
    enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    ProfileSettingsRow(icon = icon, title = title, subtitle = subtitle, enabled = enabled, onClick = onClick)
}
