package com.locket.app.ui.screens

import com.locket.app.ui.theme.*

import android.content.Context
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.DynamicRange
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
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
import com.locket.app.rust.RustLocketCore
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
private const val PAGES = "https://ngocminhvn.github.io/locket"
private data class UpdateRelease(val version: String, val build: Long, val url: String, val size: Long, val sha256: String?,
    val packageUrl: String = url, val packageSize: Long = size, val packageSha256: String? = sha256, val compressed: Boolean = false)


@Suppress("DEPRECATION")
private fun installedBuild(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

private suspend fun latestRelease(channel: String): UpdateRelease? = withContext(Dispatchers.IO) {
    require(channel == "release" || channel == "dev")
    val prefix = "$PAGES/"
    val request = Request.Builder().url(prefix + "update.json?t=" + System.currentTimeMillis())
        .header("Cache-Control", "no-cache").build()
    updateClient.newCall(request).execute().use { response ->
        if (response.code == 404) return@withContext null
        check(response.isSuccessful) { "GitHub Pages HTTP ${response.code}" }
        val bytes = response.body?.bytes() ?: error("Thiếu update.json")
        val index = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        check(index.get("schemaVersion").asInt == 1) { "Định dạng update.json không được hỗ trợ" }
        val entry = index.get(channel) ?: error("Thiếu trạng thái kênh cập nhật")
        if (entry.isJsonNull) return@withContext null
        val manifest = entry.asJsonObject
        val build = manifest.get("versionCode").asLong
        val version = manifest.get("versionName").asString
        val suffix = if (channel == "release") "R" else "dev"
        check(build > 0 && version == "1.0.$build$suffix" && manifest.get("status").asString == channel) { "Sai kênh hoặc phiên bản cập nhật" }
        val apkFile = manifest.get("apkFile").asString
        val xzFile = manifest.get("xzFile").asString
        check(apkFile == "Locket-$version.apk" && xzFile == "$apkFile.xz") { "Tên tệp cập nhật không hợp lệ" }
        val url = prefix + apkFile
        val packageUrl = prefix + xzFile
        val size = manifest.get("sizeBytes").asLong
        val packageSize = manifest.get("packageSizeBytes").asLong
        check(size in 1L..200_000_000L && packageSize in 1L..200_000_000L && manifest.get("compression").asString == "xz") { "Kích thước hoặc định dạng không hợp lệ" }
        val hash = manifest.get("sha256").asString
        val packageHash = manifest.get("packageSha256").asString
        check(hash.matches(Regex("[0-9a-fA-F]{64}")) && packageHash.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 không hợp lệ" }
        if (RustLocketCore.isAvailable) {
            // Existing Rust verifier validates a single entry with an absolute URL.
            val normalized = manifest.deepCopy().apply { addProperty("apkUrl", url) }
            val verified = RustLocketCore.verifyUpdateManifest(normalized.toString().toByteArray(Charsets.UTF_8), build, version, url, size, null)
            check(verified.equals(hash, ignoreCase = true)) { "SHA-256 manifest không khớp" }
        }
        UpdateRelease(version, build, url, size, hash, packageUrl, packageSize, packageHash, true)
    }
}

@Suppress("DEPRECATION")
private fun verifyUpdate(context: Context, apk: File, build: Long, version: String) {
    val pm = context.packageManager
    val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val installed = pm.getPackageInfo(context.packageName, flag)
    val archive = pm.getPackageArchiveInfo(apk.absolutePath, flag) ?: error("APK không hợp lệ")
    check(archive.packageName == context.packageName) { "Sai tên gói ứng dụng" }
    val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    check(code == build && code > installedBuild(context) && archive.versionName == version) { "Sai phiên bản APK" }
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
    val packed = File(dir, "Locket-${release.version}.part")
    val partial = File(dir, "Locket-${release.version}.partial.apk")
    val apk = File(dir, "Locket-${release.version}.apk")
    fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(release.packageUrl + "?v=" + release.build)
            .header("Cache-Control", "no-cache").build()
        updateClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Tải bản cập nhật thất bại (HTTP ${response.code})" }
            val body = response.body ?: error("Không nhận được gói cập nhật")
            body.byteStream().use { input ->
                packed.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var received = 0L
                    var lastProgress = 0L
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        check(received <= release.packageSize) { "Gói cập nhật vượt kích thước" }
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 120L || received == release.packageSize) {
                            lastProgress = now
                            withContext(Dispatchers.Main) { progress((received.toFloat() / release.packageSize).coerceIn(0f, 1f)) }
                        }
                    }
                    check(received == release.packageSize) { "Gói cập nhật tải chưa đủ" }
                }
            }
        }
        check(release.packageSha256.equals(hex(digest), ignoreCase = true)) { "SHA-256 gói tải không khớp" }
        val apkDigest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        packed.inputStream().buffered().use { source ->
            val input = if (release.compressed) org.tukaani.xz.XZInputStream(source, 64 * 1024) else source
            input.use { stream ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = stream.read(buffer)
                        if (n < 0) break
                        size += n
                        check(size <= release.size) { "APK vượt kích thước đã khai báo" }
                        output.write(buffer, 0, n)
                        apkDigest.update(buffer, 0, n)
                    }
                }
            }
        }
        check(size == release.size && release.sha256.equals(hex(apkDigest), ignoreCase = true)) { "APK không khớp SHA-256 hoặc kích thước" }
        verifyUpdate(context, partial, release.build, release.version)
        apk.delete()
        check(partial.renameTo(apk)) { "Không lưu được APK" }
        apk
    } finally { packed.delete(); partial.delete() }
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
    var showCameraSettings by remember { mutableStateOf(false) }
    fun checkChannel(channel: String) {
        if (busy || progress != null) return
        busy = true
        val label = if (channel == "release") "Release" else "Dev"
        scope.launch {
            try {
                val latest = latestRelease(channel)
                when {
                    latest == null -> message = "Chưa có bản $label trên GitHub Pages."
                    latest.build > installedBuild(context) -> { downloaded = null; release = latest }
                    latest.build == installedBuild(context) -> message = "Bạn đang sử dụng bản $label mới nhất."
                    else -> message = "Bản $label mới nhất (${latest.version}) cũ hơn bản đang cài ($version). Android không cho cập nhật xuống phiên bản cũ."
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.localizedMessage ?: "Không kiểm tra được cập nhật" }
            finally { busy = false }
        }
    }
    ProfileSettingsRow(
        icon = { Icon(Icons.Default.Info, null, tint = Color.White) },
        title = "Phiên bản ứng dụng",
        subtitle = if (busy) "$version · Đang kiểm tra..." else "$version · Nhấn giữ để kiểm tra Dev",
        enabled = !busy && progress == null,
        onLongClick = { checkChannel("dev") },
        onClick = { checkChannel("release") }
    )
    ModRow({ Icon(Icons.Default.Code, null, tint = Color.White) }, "GitHub", "github.com/ngocminhvn/locket") {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO))) }
        catch (e: Exception) { Toast.makeText(context, "Không mở được GitHub", Toast.LENGTH_SHORT).show() }
    }

    ProfileSettingsRow(
        icon = { Icon(Icons.Default.CameraAlt, null, tint = Color(0xFFCCCCCC)) },
        title = "Cài đặt Camera",
        trailing = { Icon(Icons.Default.ChevronRight, null, tint = LocketTextDim) },
        onClick = { showCameraSettings = true }
    )
    if (showCameraSettings) CameraSettingsDialog { showCameraSettings = false }

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
            containerColor = LocketModalBg,
            title = { Text(if (update.version.endsWith("dev")) "Có bản Dev mới" else "Có bản Release mới") },
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


// Local camera preferences: independent of the authenticated account, with real
// device capability checks. Defaults preserve the existing capture behaviour.
internal data class CameraPreferences(
    val mirror: Boolean = true,
    val selfieFlash: Boolean = false,
    val lowLight: Boolean = false,
    val stabilization: Boolean = false,
    val videoHdr: Boolean = false,
    val extension: Int = ExtensionMode.NONE,
    val showPhotoInfo: Boolean = false
)
private fun cameraPrefs(context: Context) = context.applicationContext.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
private fun readCameraPreferences(context: Context): CameraPreferences {
    val p = cameraPrefs(context)
    return CameraPreferences(
        mirror = p.getBoolean("mirror", true),
        selfieFlash = p.getBoolean("selfie_flash", false),
        lowLight = p.getBoolean("low_light", false),
        stabilization = p.getBoolean("stabilization", false),
        videoHdr = p.getBoolean("video_hdr", false),
        extension = p.getInt("extension", ExtensionMode.NONE),
        showPhotoInfo = p.getBoolean("show_photo_info", false)
    )
}
private fun saveCameraPreferences(context: Context, value: CameraPreferences) {
    cameraPrefs(context).edit().putBoolean("mirror", value.mirror)
        .putBoolean("selfie_flash", value.selfieFlash).remove("quality")
        .putBoolean("low_light", value.lowLight).putBoolean("stabilization", value.stabilization)
        .putBoolean("video_hdr", value.videoHdr).putInt("extension", value.extension)
        .putBoolean("show_photo_info", value.showPhotoInfo).apply()
}
@Composable
internal fun rememberCameraPreferences(): State<CameraPreferences> {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { cameraPrefs(context) }
    val state = remember(prefs) { mutableStateOf(readCameraPreferences(context)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state.value = readCameraPreferences(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        state.value = readCameraPreferences(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
private val cameraExtensionOptions = listOf(
    ExtensionMode.HDR to "HDR",
    ExtensionMode.NIGHT to "Chụp ban đêm",
    ExtensionMode.BOKEH to "Xóa phông",
    ExtensionMode.FACE_RETOUCH to "Làm đẹp khuôn mặt",
    ExtensionMode.AUTO to "Chế độ tự động"
)
private data class CameraFeatureSupport(
    val front: Boolean, val lowLight: Boolean, val stabilization: Boolean,
    val videoHdr: Boolean, val extensions: Set<Int>, val extensionQueryOk: Boolean
)
@Composable
private fun CameraSettingsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val settings by rememberCameraPreferences()
    var support by remember { mutableStateOf<CameraFeatureSupport?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
            val selectors = listOf(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)
                .filter { provider.hasCamera(it) }
            val infos = selectors.map { provider.getCameraInfo(it) }
            val manager = try {
                withContext(Dispatchers.IO) { ExtensionsManager.getInstanceAsync(context, provider).get() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            support = CameraFeatureSupport(
                front = CameraSelector.DEFAULT_FRONT_CAMERA in selectors,
                lowLight = infos.any { it.isLowLightBoostSupported },
                stabilization = infos.any { Preview.getPreviewCapabilities(it).isStabilizationSupported },
                videoHdr = infos.any { DynamicRange.HLG_10_BIT in Recorder.getVideoCapabilities(it).supportedDynamicRanges },
                extensions = cameraExtensionOptions.map { it.first }.filter { mode ->
                    manager != null && selectors.any { selector -> manager.isExtensionAvailable(selector, mode) }
                }.toSet(),
                extensionQueryOk = manager != null
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    fun save(value: CameraPreferences) = saveCameraPreferences(context, value)
    val caps = support
    val unavailable = if (failed) "Không kiểm tra được thiết bị" else "Đang kiểm tra thiết bị..."
    fun hint(supported: Boolean?, text: String): String = when (supported) {
        true -> text
        false -> "Thiết bị không hỗ trợ"
        null -> unavailable
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Cài đặt Camera", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CameraOption("Hiển thị thông tin ảnh", "Xem thông tin ảnh sau xử lý ở màn hình gửi", settings.showPhotoInfo, true) {
                    save(settings.copy(showPhotoInfo = it))
                }
                CameraOption("Lật ảnh selfie", "Lưu ảnh giống khung xem trước", settings.mirror, true) {
                    save(settings.copy(mirror = it))
                }
                CameraOption("Flash màn hình", hint(caps?.front, "Chiếu sáng khi chụp camera trước"), settings.selfieFlash,
                    caps?.front == true || settings.selfieFlash) { save(settings.copy(selfieFlash = it)) }
                CameraOption("Tăng sáng khi thiếu sáng", hint(caps?.lowLight, "Low Light Boost trên camera hỗ trợ"), settings.lowLight,
                    caps?.lowLight == true || settings.lowLight) { save(settings.copy(lowLight = it)) }
                CameraOption("Chống rung xem trước", hint(caps?.stabilization, "Ổn định khung hình khi thiết bị hỗ trợ"), settings.stabilization,
                    caps?.stabilization == true || settings.stabilization) { save(settings.copy(stabilization = it)) }
                CameraOption("Video HDR", hint(caps?.videoHdr, "HLG 10-bit khi quay video"), settings.videoHdr,
                    caps?.videoHdr == true || settings.videoHdr) { save(settings.copy(videoHdr = it)) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color.White.copy(alpha = 0.15f))
                Text("Chế độ xử lý ảnh", color = LocketYellow, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Chọn một chế độ. Áp dụng khi chụp ảnh; video dùng camera thường.", color = LocketTextDim, fontSize = 12.sp)
                cameraExtensionOptions.forEach { (mode, title) ->
                    val supported = caps?.let { if (it.extensionQueryOk) mode in it.extensions else null }
                    val selected = settings.extension == mode
                    CameraOption(title, hint(supported, "Xử lý ảnh bằng camera của hãng"), selected,
                        supported == true || selected) { enabled ->
                        save(settings.copy(extension = if (enabled) mode else ExtensionMode.NONE))
                    }
                }
                Text("Tùy chọn chỉ áp dụng trên camera trước/sau và cấu hình được hỗ trợ. Nếu không tương thích, ứng dụng dùng camera thường.",
                    color = LocketTextDim, fontSize = 12.sp)
            }
        },
        containerColor = LocketModalBg,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        confirmButton = { TextButton(onClick = onClose) { Text("Xong", color = LocketYellow) } }
    )
}
@Composable
private fun CameraOption(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = if (enabled) Color.White else LocketTextDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = LocketTextDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LocketYellow))
    }
}
