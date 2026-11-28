package com.locket.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.Canvas as AndroidCanvas
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.zxing.EncodeHintType
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.liquidGlassEffect
import com.locket.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun ProfileScreen(
    viewModel: LocketViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var showQrModal by remember { mutableStateOf(false) }
    var showBlockedUsers by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var isUploadingAvatar by remember { mutableStateOf(false) }
    var goldBadgeActive by remember { mutableStateOf(AppearancePrefs.badge(context)) }
    var appearanceSheet by remember { mutableStateOf<String?>(null) }

    // Chon anh dai dien tu gallery -> nen ve JPEG -> upload that len
    // Storage users/{uid}/public/profile_pic.jpg (xem ApiClient
    // .uploadProfilePicture, dung duong dan goc da xac minh).
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || isUploadingAvatar) return@rememberLauncherForActivityResult
        isUploadingAvatar = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    } ?: return@withContext null
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        out.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bytes == null) {
                isUploadingAvatar = false
                Toast.makeText(context, "Không đọc được ảnh đã chọn", Toast.LENGTH_SHORT).show()
                return@launch
            }
            viewModel.updateProfilePicture(
                imageBytes = bytes,
                onSuccess = {
                    isUploadingAvatar = false
                    Toast.makeText(context, "Đã đổi ảnh đại diện", Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    isUploadingAvatar = false
                    Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    val user by viewModel.currentUser.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val blockedUsers by viewModel.blockedUsers.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The navigation controls share the same vertical space as the avatar.
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Box(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(116.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                            .background(LocketBlack).border(3.dp, if (goldBadgeActive) LocketYellow else Color.Transparent, CircleShape)
                            .padding(7.dp).clip(CircleShape).background(Color(0xFF2C2C2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (user.profilePictureUrl.isNotBlank()) {
                            AsyncImage(
                                model = user.profilePictureUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        }
                    }

                    if (goldBadgeActive) Box(
                        modifier = Modifier.size(40.dp)
                            .background(LocketYellow, CircleShape)
                            .border(3.dp, LocketBlack, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Favorite, null, tint = LocketBlack, modifier = Modifier.size(20.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = LocketTextDim)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = LocketYellow, strokeWidth = 2.dp)
                        }
                        IconButton(onClick = { showQrModal = true }) {
                            Icon(Icons.Default.QrCode, "Mã QR", tint = LocketYellow)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            // Name & Edit photo — "Chỉnh ảnh" mo gallery that (upload
            // Storage + changeProfileInfo), "Đổi tên" mo dialog doi ten that.
            Text(
                text = user.displayName.ifBlank { user.username.ifBlank { "Tài khoản Locket" } },
                color = Color.White,
                style = profileTextStyle(24.sp, 29.sp, FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isUploadingAvatar) "Đang tải ảnh lên..." else "Chỉnh ảnh",
                    color = LocketYellow,
                    style = profileTextStyle(14.sp, 18.sp, FontWeight.SemiBold),
                    modifier = Modifier.clickable(enabled = !isUploadingAvatar) {
                        avatarPicker.launch("image/*")
                    }
                )
                Text(
                    text = "Đổi tên",
                    color = LocketYellow,
                    style = profileTextStyle(14.sp, 18.sp, FontWeight.SemiBold),
                    modifier = Modifier.clickable { showEditNameDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Separate rounded cards with the narrow gap from the reference.
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(Color(0xFF1E1E1E))
                        .heightIn(min = 100.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // No Rollcall thumbnail or participant count is available in the current data model.
                    Icon(Icons.Outlined.Campaign, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Rollcall", color = Color.White, style = profileTextStyle(17.sp, 21.sp, FontWeight.Bold))
                        Text("Chia sẻ ảnh với bạn bè", color = LocketTextDim, style = profileTextStyle(16.sp, 21.sp, FontWeight.Medium))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .drawBehind {
                                drawRoundRect(
                                    color = LocketYellow.copy(alpha = 0.32f),
                                    cornerRadius = CornerRadius(24.dp.toPx()),
                                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                                )
                            }
                            .clickable { Toast.makeText(context, "Chọn ảnh Rollcall", Toast.LENGTH_SHORT).show() }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("CHỌN ẢNH", color = LocketYellow, style = profileTextStyle(14.sp, 18.sp, FontWeight.Bold))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 24.dp, bottomEnd = 24.dp))
                        .background(Color(0xFF1E1E1E))
                        .heightIn(min = 84.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .background(LocketBlack).border(2.dp, LocketYellow, CircleShape)
                            .padding(4.dp).clip(CircleShape).background(Color(0xFF2C2C2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (user.profilePictureUrl.isNotBlank()) {
                            AsyncImage(user.profilePictureUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mời bạn bè tham gia Locket!", color = Color.White, style = profileTextStyle(16.sp, 20.sp, FontWeight.Bold))
                        if (user.username.isNotBlank()) {
                            Text("locket.cam/${user.username}", color = LocketTextDim, style = profileTextStyle(15.sp, 19.sp, FontWeight.Medium))
                        }
                    }
                    IconButton(onClick = { showQrModal = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Share, null, tint = Color.LightGray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Locket Gold
            SectionTitle("Locket Gold")
            SettingsItem(
                icon = {
                    Box(Modifier.size(20.dp).background(LocketYellow, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Favorite, null, tint = LocketBlack, modifier = Modifier.size(14.dp))
                    }
                },
                title = "Choose app icon",
                onClick = { appearanceSheet = "icon" }
            )
            SettingsItem(
                icon = {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.align(Alignment.BottomStart).size(19.dp).border(2.dp, LocketYellow, RoundedCornerShape(3.dp)))
                        Icon(Icons.Default.Edit, null, tint = LocketYellow, modifier = Modifier.align(Alignment.TopEnd).size(19.dp).background(LocketBlack, CircleShape))
                    }
                },
                title = "Chỉnh sửa khung widget",
                onClick = { appearanceSheet = "widget" }
            )
            SettingsItem(
                icon = { Icon(Icons.Default.Brush, null, tint = LocketYellow) },
                title = "Choose camera theme",
                onClick = { appearanceSheet = "camera" }
            )
            SettingsItem(
                icon = {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Stars, null, tint = LocketYellow, modifier = Modifier.fillMaxSize())
                        Icon(Icons.Default.Favorite, null, tint = LocketBlack, modifier = Modifier.size(14.dp).background(LocketYellow, CircleShape))
                    }
                },
                title = "Huy hiệu Locket Gold",
                trailing = {
                    Switch(
                        checked = goldBadgeActive,
                        onCheckedChange = { goldBadgeActive = it; AppearancePrefs.setBadge(context, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = LocketYellow
                        )
                    )
                }
            )
            SettingsItem(
                icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, null, tint = LocketYellow) },
                title = "Quản lý Gói đăng ký",
                onClick = { appearanceSheet = "subscription" }
            )

            Spacer(modifier = Modifier.height(16.dp))

            SectionTitle("Mod Center")
            ModCenterSettings(viewModel)
            Spacer(modifier = Modifier.height(16.dp))

            SectionTitle("Tổng quát")
            GeneralProfileSettings(viewModel, user.displayName) { showEditNameDialog = true }
            Spacer(modifier = Modifier.height(16.dp))

            // Section: Riêng tư & bảo mật
            SectionTitle("Riêng tư & bảo mật")
            // Danh sach that: doc tu Firestore users/{uid}/blocked_users
            // (xem ApiClient.fetchBlockedUsers), khong phai Toast gia.
            SettingsItem(
                icon = { Icon(Icons.Default.Block, null, tint = Color.White) },
                title = "Tài khoản bị chặn",
                onClick = {
                    viewModel.refreshBlockedUsers()
                    showBlockedUsers = true
                }
            )
            SettingsItem(
                icon = { Icon(Icons.Default.Security, null, tint = Color.White) },
                title = "Privacy and Data",
                onClick = { Toast.makeText(context, "Privacy", Toast.LENGTH_SHORT).show() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Vùng nguy hiểm
            SectionTitle("Vùng nguy hiểm")
            SettingsItem(
                icon = { Icon(Icons.Default.Delete, null, tint = LocketRed) },
                title = "Xóa tài khoản",
                textColor = LocketRed,
                onClick = { Toast.makeText(context, "Yêu cầu xóa tài khoản", Toast.LENGTH_SHORT).show() }
            )
            SettingsItem(
                icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = Color.White) },
                title = "Đăng xuất",
                onClick = {
                    viewModel.logout()
                    Toast.makeText(context, "Đã đăng xuất", Toast.LENGTH_SHORT).show()
                }
            )
        }

        appearanceSheet?.let { page ->
            AppearanceSheet(page, viewModel, onDismiss = { appearanceSheet = null })
        }

        // Modals & Dialogs
        if (showQrModal) {
            QrShareBottomSheet(user = user, viewModel = viewModel, onDismiss = { showQrModal = false })
        }

        if (showEditNameDialog) {
            EditNameDialog(
                currentDisplayName = user.displayName,
                viewModel = viewModel,
                onDismiss = { showEditNameDialog = false }
            )
        }

        if (showBlockedUsers) {
            BlockedUsersBottomSheet(
                viewModel = viewModel,
                blockedUsers = blockedUsers,
                onDismiss = { showBlockedUsers = false }
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = Color.White,
        style = profileTextStyle(20.sp, 25.sp, FontWeight.Bold),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 17.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: @Composable () -> Unit,
    title: String,
    textColor: Color = Color.White,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    ProfileSettingsRow(icon = icon, title = title, titleColor = textColor, trailing = trailing, onClick = onClick)
}

/**
 * Danh sach that cac tai khoan bi chan (bottom sheet, cung kieu overlay
 * nhu QrShareBottomSheet: nen mo full man hinh + khung duoi bo tron 32dp,
 * nen 0xFF141416).
 * Nguon that: viewModel.blockedUsers — REST Firestore
 * users/{uid}/blocked_users (xem ApiClient.fetchBlockedUsers). Khong co ai
 * thi hien dong "Không có tài khoản bị chặn" dua tren du lieu that, khong
 * phai Toast gia. Moi hang co nut "Bỏ chặn" goi unblockUser that.
 */
@Composable
private fun BlockedUsersBottomSheet(
    viewModel: LocketViewModel,
    blockedUsers: List<com.locket.app.model.BlockedUser>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var unblockingUid by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(Color(0xFF141416))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .background(Color(0xFF48484A), RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Tài khoản bị chặn",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (blockedUsers.isEmpty()) {
                Text(
                    text = "Không có tài khoản bị chặn",
                    color = LocketTextDim,
                    fontSize = 16.sp
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(blockedUsers, key = { it.uid }) { blocked ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2C2C2E)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (blocked.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = blocked.avatarUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Person,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = blocked.displayName.ifBlank { blocked.uid },
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )

                            TextButton(
                                onClick = {
                                    if (unblockingUid != null) return@TextButton
                                    unblockingUid = blocked.uid
                                    viewModel.unblockUser(
                                        userUid = blocked.uid,
                                        onSuccess = {
                                            Toast.makeText(context, "Đã bỏ chặn ${blocked.displayName.ifBlank { blocked.uid }}", Toast.LENGTH_SHORT).show()
                                            unblockingUid = null
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                                            unblockingUid = null
                                        }
                                    )
                                },
                                enabled = unblockingUid == null
                            ) {
                                Text("Bỏ chặn", color = LocketRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * QR moi THAT: ma QR ve tu link moi that (generateInviteToken +
 * createDynamicLink cua goc — xem ApiClient.createInviteLink), khong
 * phai icon tinh. Layout sheet giu nguyen nhu cu (nen vang + avatar
 * giua + 4 nut hanh dong). "Save QR" luu bitmap QR that vao Thu vien
 * qua MediaStore va bao ket qua that; "Sao chép" copy link moi that.
 * Messenger/Zalo van la Toast cho — ngoai pham vi batch nay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrShareBottomSheet(
    user: com.locket.app.model.User,
    viewModel: LocketViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inviteLink by viewModel.inviteLink.collectAsState()
    val inviteLinkLoading by viewModel.inviteLinkLoading.collectAsState()
    var isSavingQr by remember { mutableStateOf(false) }
    var qrBitmap by remember(inviteLink, user.profilePictureUrl) { mutableStateOf<Bitmap?>(null) }
    var rendering by remember(inviteLink, user.profilePictureUrl) { mutableStateOf(false) }
    var renderError by remember(inviteLink, user.profilePictureUrl) { mutableStateOf(false) }

    fun loadLink() = viewModel.loadInviteLink { err ->
        Toast.makeText(context, "Lỗi tạo link mời: $err", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) { loadLink() }
    LaunchedEffect(inviteLink, user.profilePictureUrl) {
        val link = inviteLink?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        rendering = true
        try {
            val avatar = if (user.profilePictureUrl.isNotBlank()) {
                try {
                    val result = context.imageLoader.execute(ImageRequest.Builder(context)
                        .data(user.profilePictureUrl).size(320).allowHardware(false).build())
                    (result as? SuccessResult)?.drawable?.toBitmap()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            } else null
            qrBitmap = withContext(Dispatchers.Default) { generateProfileQrCard(link, avatar) }
            renderError = qrBitmap == null
        } finally { rendering = false }
    }

    fun shareLink(packageName: String?) {
        val link = inviteLink?.takeIf { it.isNotBlank() } ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        try {
            if (packageName != null) context.startActivity(Intent(send).setPackage(packageName))
            else context.startActivity(Intent.createChooser(send, "Chia sẻ hồ sơ"))
        } catch (_: android.content.ActivityNotFoundException) {
            try { context.startActivity(Intent.createChooser(send, "Chia sẻ hồ sơ")) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "Không có ứng dụng chia sẻ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF242424),
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp),
        dragHandle = {
            Box(Modifier.padding(vertical = 12.dp).size(width = 34.dp, height = 5.dp)
                .background(Color(0xFF666666), RoundedCornerShape(3.dp)))
        }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 22.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.fillMaxWidth(0.75f).widthIn(max = 340.dp).aspectRatio(0.875f)
                    .shadow(16.dp, RoundedCornerShape(32.dp), ambientColor = LocketYellow, spotColor = LocketYellow)
                    .clip(RoundedCornerShape(32.dp)).background(LocketYellow),
                contentAlignment = Alignment.Center
            ) {
                val card = qrBitmap
                if (card != null) {
                    Image(card.asImageBitmap(), "Mã QR mời bạn bè", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else if (inviteLinkLoading || rendering) {
                    CircularProgressIndicator(color = Color.Black)
                } else {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (renderError) "Không tạo được mã QR" else "Chưa có link mời", color = Color.Black)
                        if (!renderError) TextButton(onClick = { loadLink() }) { Text("Thử lại", color = Color.Black) }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("Share your profile!", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                ShareActionItem("Save QR", Icons.Default.Download) {
                    val bitmap = qrBitmap ?: return@ShareActionItem
                    if (isSavingQr) return@ShareActionItem
                    isSavingQr = true
                    scope.launch {
                        try {
                            val saved = withContext(Dispatchers.IO) { saveQrBitmapToGallery(context, bitmap) }
                            saved.onSuccess {
                                Toast.makeText(context, "Đã lưu QR vào Thư viện", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(context, "Lỗi lưu mã QR: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        } finally { isSavingQr = false }
                    }
                }
                ShareActionItem("Sao chép", Icons.Default.Link) {
                    val link = inviteLink?.takeIf { it.isNotBlank() } ?: return@ShareActionItem
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Locket invite link", link))
                    Toast.makeText(context, "Đã sao chép link mời", Toast.LENGTH_SHORT).show()
                }
                ShareActionItem("Messenger", Icons.Default.Chat) { shareLink("com.facebook.orca") }
                ShareActionItem("Zalo", Icons.Default.Share) { shareLink("com.zing.zalo") }
                ShareActionItem("Instagram", Icons.Default.CameraAlt) { shareLink("com.instagram.android") }
                ShareActionItem("Khác", Icons.Default.Share) { shareLink(null) }
            }
        }
    }
}

/**
 * Render the real invite URL as a yellow QR card. Validate the rendered
 * artwork (including the avatar) with ZXing before showing or saving it.
 * If the avatar obscures too many modules, shrink it or use square modules.
 */
private fun generateProfileQrCard(content: String, avatar: Bitmap?): Bitmap? {
    return try {
        val code = Encoder.encode(content, ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val matrix = code.matrix ?: return null
        val n = matrix.width
        val width = 840
        val height = 960
        val module = 700f / (n + 8)
        val qrSize = module * n
        val startX = (width - qrSize) / 2f
        val startY = 36f + 4 * module
        val black = android.graphics.Color.BLACK
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        fun render(dots: Boolean, avatarFraction: Float): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bitmap)
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xFFFF9C00.toInt(), 0xFFFFCF00.toInt(), 0xFFFFA000.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val background = paint.shader
            paint.shader = null
            paint.color = black
            fun inFinder(x: Int, y: Int): Boolean =
                (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)

            for (y in 0 until n) for (x in 0 until n) {
                if (matrix.get(x, y).toInt() != 1 || (dots && inFinder(x, y))) continue
                val px = startX + x * module
                val py = startY + y * module
                // Timing and format information remain solid to preserve detection.
                if (!dots || x == 6 || y == 6 || x == 8 || y == 8) {
                    canvas.drawRect(px, py, px + module, py + module, paint)
                } else canvas.drawCircle(px + module / 2, py + module / 2, module * 0.46f, paint)
            }
            if (dots) {
                for ((x, y) in listOf(0 to 0, (n - 7) to 0, 0 to (n - 7))) {
                    val px = startX + x * module
                    val py = startY + y * module
                    paint.color = black
                    canvas.drawRoundRect(RectF(px, py, px + 7 * module, py + 7 * module), module * 1.7f, module * 1.7f, paint)
                    paint.shader = background
                    canvas.drawRoundRect(RectF(px + module, py + module, px + 6 * module, py + 6 * module), module, module, paint)
                    paint.shader = null
                    paint.color = black
                    canvas.drawRoundRect(RectF(px + 2 * module, py + 2 * module, px + 5 * module, py + 5 * module), module * 0.7f, module * 0.7f, paint)
                }
            }

            if (avatar != null && avatarFraction > 0f) {
                val cx = width / 2f
                val cy = startY + qrSize / 2f
                val radius = qrSize * avatarFraction / 2f
                paint.shader = background
                canvas.drawCircle(cx, cy, radius + module * 0.8f, paint)
                val shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val scale = (radius * 2f) / minOf(avatar.width, avatar.height)
                shader.setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(cx - avatar.width * scale / 2f, cy - avatar.height * scale / 2f)
                })
                paint.shader = shader
                canvas.drawCircle(cx, cy, radius, paint)
                paint.shader = null
            }
            paint.color = black
            paint.textSize = 45f
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textAlign = Paint.Align.LEFT
            val left = "Add me on "
            val right = " Locket"
            val iconSize = 52f
            val total = paint.measureText(left) + iconSize + paint.measureText(right)
            val x = (width - total) / 2
            val baseline = 874f
            canvas.drawText(left, x, baseline, paint)
            val iconX = x + paint.measureText(left)
            canvas.drawRoundRect(RectF(iconX, baseline - 43f, iconX + iconSize, baseline + 9f), 13f, 13f, paint)
            paint.color = 0xFFFFC000.toInt()
            val heart = android.graphics.Path().apply {
                moveTo(iconX + 26f, baseline - 3f)
                cubicTo(iconX - 5f, baseline - 24f, iconX + 15f, baseline - 44f, iconX + 26f, baseline - 28f)
                cubicTo(iconX + 37f, baseline - 44f, iconX + 57f, baseline - 24f, iconX + 26f, baseline - 3f)
                close()
            }
            canvas.drawPath(heart, paint)
            paint.color = black
            canvas.drawText(right, iconX + iconSize, baseline, paint)
            return bitmap
        }

        fun valid(bitmap: Bitmap): Boolean = try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            ))).text == content
        } catch (_: Exception) { false }

        for ((dots, fraction) in listOf(true to 0.30f, true to 0.24f, true to 0.17f,
            true to 0f, false to 0.20f, false to 0f)) {
            val bitmap = render(dots, fraction)
            if (valid(bitmap)) return bitmap
            bitmap.recycle()
        }
        null
    } catch (_: Exception) { null }
}

/**
 * Luu bitmap QR that vao Thu vien (Pictures/Locket) qua MediaStore.
 * Tra ve ten file that khi luu duoc, loi that khi khong luu duoc.
 */
private fun saveQrBitmapToGallery(context: Context, bitmap: Bitmap): Result<String> {
    return try {
        val fileName = "locket-qr-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Locket")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return Result.failure(Exception("Không tạo được file trong Thư viện"))
        context.contentResolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return Result.failure(Exception("Không ghi được ảnh QR"))
            }
        } ?: return Result.failure(Exception("Không mở được file trong Thư viện"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, done, null, null)
        }
        Result.success(fileName)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Dialog doi ten hien thi that (changeProfileInfo {first_name,
 * last_name}). Goc chi luu first_name/last_name rieng, khong co truong
 * displayName — tach ten hien tai: tu cuoi = last_name, phan truoc =
 * first_name.
 */
@Composable
private fun EditNameDialog(
    currentDisplayName: String,
    viewModel: LocketViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val parts = remember(currentDisplayName) {
        currentDisplayName.trim().split(" ").filter { it.isNotBlank() }
    }
    var firstName by remember {
        mutableStateOf(parts.dropLast(1).joinToString(" "))
    }
    var lastName by remember {
        mutableStateOf(parts.lastOrNull() ?: "")
    }
    var isLoading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E22),
        title = { Text("Đổi tên hồ sơ", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("Tên") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Họ") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (firstName.isBlank() && lastName.isBlank()) {
                        Toast.makeText(context, "Nhập tên hiển thị", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isLoading = true
                    viewModel.updateProfileName(
                        firstName = firstName,
                        lastName = lastName,
                        onSuccess = {
                            isLoading = false
                            Toast.makeText(context, "Đã lưu tên", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        onError = { err ->
                            isLoading = false
                            Toast.makeText(context, "Lỗi: $err", Toast.LENGTH_LONG).show()
                        }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = LocketYellow)
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black)
                else Text("Lưu", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy", color = Color.White) }
        }
    )
}

@Composable
private fun ShareActionItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFF414143)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
