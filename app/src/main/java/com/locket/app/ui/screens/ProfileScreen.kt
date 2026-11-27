package com.locket.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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
    var showLoginDialog by remember { mutableStateOf(false) }
    var showPhoneLoginDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showBlockedUsers by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showEditUsernameDialog by remember { mutableStateOf(false) }
    var isUploadingAvatar by remember { mutableStateOf(false) }
    var goldBadgeActive by remember { mutableStateOf(true) }

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
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: Back arrow & QR code button (Matches 05-profile.jpg)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = Color.White
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                            color = LocketYellow,
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = { showQrModal = true }) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "Mã QR",
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Large Avatar Circle with Yellow border & Heart Badge
            Box(
                modifier = Modifier.size(116.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E))
                        .border(3.5.dp, LocketYellow, CircleShape),
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
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }

                // Yellow Heart Badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(LocketYellow, CircleShape)
                        .border(2.5.dp, Color.Black, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("💛", fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Name & Edit photo — "Chỉnh ảnh" mo gallery that (upload
            // Storage + changeProfileInfo), "Sửa tên" mo dialog doi ten that.
            Text(
                text = user.displayName.ifBlank { user.username.ifBlank { "Tài khoản Locket" } },
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isUploadingAvatar) "Đang tải ảnh lên..." else "Chỉnh ảnh",
                    color = LocketYellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = !isUploadingAvatar) {
                        avatarPicker.launch("image/*")
                    }
                )
                Text(
                    text = "Sửa tên",
                    color = LocketYellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showEditNameDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Rollcall Card & Invite Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1C1C1E))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Campaign,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Rollcall", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Một người bạn đã chia sẻ!", color = LocketTextDim, fontSize = 13.sp)
                    }
                    Button(
                        onClick = { Toast.makeText(context, "Chọn ảnh Rollcall", Toast.LENGTH_SHORT).show() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LocketYellow),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("CHỌN ẢNH", color = LocketYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = Color(0xFF2C2C2E))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, LocketYellow, CircleShape)
                            .background(Color(0xFF2C2C2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mời bạn bè tham gia Locket!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        // Chi hien link moi khi co username that; username trong thi
                        // khong hien "locket.cam/" trong (bug anh chup man hinh).
                        if (user.username.isNotBlank()) {
                            Text("locket.cam/${user.username}", color = LocketTextDim, fontSize = 13.sp)
                        }
                    }
                    IconButton(onClick = { showQrModal = true }) {
                        Icon(Icons.Default.Share, null, tint = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Locket Gold
            SectionTitle("Locket Gold")
            SettingsItem(
                icon = { Icon(Icons.Default.Favorite, null, tint = LocketYellow) },
                title = "Choose app icon",
                onClick = { Toast.makeText(context, "Chọn icon", Toast.LENGTH_SHORT).show() }
            )
            SettingsItem(
                icon = { Icon(Icons.Default.Edit, null, tint = LocketYellow) },
                title = "Chỉnh sửa khung widget",
                onClick = { Toast.makeText(context, "Sửa widget", Toast.LENGTH_SHORT).show() }
            )
            SettingsItem(
                icon = { Icon(Icons.Default.Brush, null, tint = LocketYellow) },
                title = "Choose camera theme",
                onClick = { Toast.makeText(context, "Đổi giao diện", Toast.LENGTH_SHORT).show() }
            )
            SettingsItem(
                icon = { Icon(Icons.Default.Stars, null, tint = LocketYellow) },
                title = "Huy hiệu Locket Gold",
                trailing = {
                    Switch(
                        checked = goldBadgeActive,
                        onCheckedChange = { goldBadgeActive = it },
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
                onClick = { Toast.makeText(context, "Gói đăng ký Locket Gold", Toast.LENGTH_SHORT).show() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            SectionTitle("Mod Center")
            ModCenterSettings()
            Spacer(modifier = Modifier.height(24.dp))

            // Section: Tài khoản & Kết nối Live API Locket
            SectionTitle("Tài khoản Live Locket")
            if (viewModel.isLoggedIn) {
                SettingsItem(
                    icon = { Icon(Icons.Default.CheckCircle, null, tint = LocketYellow) },
                    title = "Đang kết nối: ${user.email.ifBlank { user.username }}",
                    trailing = {
                        TextButton(onClick = {
                            viewModel.logout()
                            Toast.makeText(context, "Đã đăng xuất", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Đăng xuất", color = LocketRed, fontWeight = FontWeight.Bold)
                        }
                    }
                )
                // Ten nguoi dung that (setUsername V1) — hien username hien
                // tai, bam de dat/doi; loi server (vd 401/App Check) bao that.
                SettingsItem(
                    icon = { Icon(Icons.Default.AlternateEmail, null, tint = LocketYellow) },
                    title = if (user.username.isNotBlank()) "Tên người dùng: @${user.username}" else "Đặt tên người dùng",
                    onClick = { showEditUsernameDialog = true }
                )
                SettingsItem(
                    icon = { Icon(Icons.Default.Sync, null, tint = LocketYellow) },
                    title = "Đồng bộ lại toàn bộ dữ liệu từ server",
                    onClick = {
                        viewModel.syncAllData()
                        Toast.makeText(context, "Đang đồng bộ từ Locket...", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                SettingsItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.Login, null, tint = LocketYellow) },
                    title = "Đăng nhập bằng Email & Mật khẩu",
                    onClick = { showLoginDialog = true }
                )
                SettingsItem(
                    icon = { Icon(Icons.Default.Phone, null, tint = LocketYellow) },
                    title = "Đăng nhập bằng Số điện thoại (SMS)",
                    onClick = { showPhoneLoginDialog = true }
                )
                SettingsItem(
                    icon = { Icon(Icons.Default.Key, null, tint = LocketYellow) },
                    title = "Nhập Firebase ID Token trực tiếp",
                    onClick = { showTokenDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

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

            Spacer(modifier = Modifier.height(24.dp))

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

        if (showEditUsernameDialog) {
            EditUsernameDialog(
                currentUsername = user.username,
                viewModel = viewModel,
                onDismiss = { showEditUsernameDialog = false }
            )
        }

        if (showLoginDialog) {
            EmailLoginDialog(viewModel = viewModel, onDismiss = { showLoginDialog = false })
        }

        if (showPhoneLoginDialog) {
            PhoneLoginDialog(viewModel = viewModel, onDismiss = { showPhoneLoginDialog = false })
        }

        if (showTokenDialog) {
            DirectTokenDialog(viewModel = viewModel, onDismiss = { showTokenDialog = false })
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
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
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
@Composable
private fun QrShareBottomSheet(
    user: com.locket.app.model.User,
    viewModel: LocketViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inviteLink by viewModel.inviteLink.collectAsState()
    val inviteLinkLoading by viewModel.inviteLinkLoading.collectAsState()
    var isSavingQr by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadInviteLink { err ->
            Toast.makeText(context, "Lỗi tạo link mời: $err", Toast.LENGTH_LONG).show()
        }
    }

    // QR that tu link moi; null = chua co link (dang tai hoac loi).
    val qrBitmap = remember(inviteLink) {
        inviteLink?.let { generateQrBitmap(it, 512) }
    }

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

            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(LocketYellow)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(170.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Mã QR mời bạn bè",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (inviteLinkLoading) {
                            CircularProgressIndicator(color = Color.Black)
                        } else {
                            // Chua co link (loi mang/server): giu icon cu lam
                            // placeholder, khong ve QR gia tu chuoi rong.
                            Icon(
                                imageVector = Icons.Default.QrCode2,
                                contentDescription = "QR Code",
                                tint = Color.Black,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .border(2.dp, LocketYellow, CircleShape)
                                .background(Color.White),
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
                                Icon(Icons.Default.Person, null, tint = Color.Black, modifier = Modifier.size(30.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Add me on", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("💛 Locket", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("Share your profile!", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                ShareActionItem("Save QR", Icons.Default.Download) {
                    val bitmap = qrBitmap
                    if (bitmap == null) {
                        Toast.makeText(context, "Chưa tạo được mã QR, thử lại sau", Toast.LENGTH_SHORT).show()
                        return@ShareActionItem
                    }
                    if (isSavingQr) return@ShareActionItem
                    isSavingQr = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            saveQrBitmapToGallery(context, bitmap)
                        }
                        isSavingQr = false
                        saved.onSuccess { fileName ->
                            Toast.makeText(context, "Đã lưu mã QR ($fileName) vào Thư viện", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(context, "Lỗi lưu mã QR: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                ShareActionItem("Sao chép", Icons.Default.Link) {
                    val link = inviteLink
                    if (link.isNullOrBlank()) {
                        Toast.makeText(context, "Chưa tạo được link mời, thử lại sau", Toast.LENGTH_SHORT).show()
                        return@ShareActionItem
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Locket invite link", link))
                    Toast.makeText(context, "Đã sao chép link mời", Toast.LENGTH_SHORT).show()
                }
                ShareActionItem("Messenger", Icons.Default.Chat) {
                    Toast.makeText(context, "Chia sẻ Messenger", Toast.LENGTH_SHORT).show()
                }
                ShareActionItem("Zalo", Icons.Default.Share) {
                    Toast.makeText(context, "Chia sẻ Zalo", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Ve QR that tu 1 chuoi (link moi) bang ZXing — khong phai icon tinh. */
private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x, y,
                    if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
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
        title = { Text("Sửa tên hồ sơ", color = Color.White, fontWeight = FontWeight.Bold) },
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

/**
 * Dialog dat/doi username that (setUsername V1). Hien username hien tai;
 * loi server (vd username da co nguoi dung / 401) bao that, khong bao
 * success gia. Chua live-verify (xem ApiClient.setUsername).
 */
@Composable
private fun EditUsernameDialog(
    currentUsername: String,
    viewModel: LocketViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var username by remember { mutableStateOf(currentUsername) }
    var isLoading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E22),
        title = { Text("Tên người dùng", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Tên người dùng dùng trong link mời locket.cam/<tên của bạn>.",
                    color = LocketTextDim, fontSize = 13.sp
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (username.isBlank()) {
                        Toast.makeText(context, "Nhập tên người dùng", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isLoading = true
                    viewModel.updateUsername(
                        username = username,
                        onSuccess = {
                            isLoading = false
                            Toast.makeText(context, "Đã lưu tên người dùng", Toast.LENGTH_SHORT).show()
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(0xFF242426)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun EmailLoginDialog(viewModel: LocketViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E22),
        title = { Text("Đăng nhập Email Locket", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Nhập thông tin tài khoản Locket của bạn:", color = LocketTextDim, fontSize = 13.sp)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mật khẩu") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (email.isBlank() || password.isBlank()) return@Button
                    isLoading = true
                    viewModel.loginWithEmail(
                        email = email.trim(),
                        pass = password,
                        onSuccess = {
                            isLoading = false
                            Toast.makeText(context, "Đăng nhập thành công! 💛", Toast.LENGTH_SHORT).show()
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
                else Text("Đăng nhập", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy", color = Color.White) }
        }
    )
}

@Composable
private fun PhoneLoginDialog(viewModel: LocketViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("+84") }
    var code by remember { mutableStateOf("") }
    var isCodeSent by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E22),
        title = { Text("Đăng nhập SĐT Locket", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isCodeSent) {
                    Text("Nhập số điện thoại (định dạng E.164 ví dụ +84912345678):", color = LocketTextDim, fontSize = 13.sp)
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Số điện thoại") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Nhập mã OTP vừa nhận:", color = LocketTextDim, fontSize = 13.sp)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Mã OTP") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isCodeSent) {
                        isLoading = true
                        scope.launch {
                            val res = viewModel.apiClient.sendVerificationCode(phone.trim())
                            isLoading = false
                            if (res.isSuccess) {
                                isCodeSent = true
                                Toast.makeText(context, "Đã gửi mã xác nhận!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Lỗi: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        isLoading = true
                        scope.launch {
                            val authRes = viewModel.apiClient.checkVerificationCode(phone.trim(), code.trim())
                            isLoading = false
                            if (authRes.success && authRes.idToken != null) {
                                viewModel.loginWithDirectToken(authRes.idToken, authRes.uid ?: "user_me") {
                                    Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                            } else {
                                Toast.makeText(context, "Lỗi: ${authRes.errorMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LocketYellow)
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black)
                else Text(if (!isCodeSent) "Gửi mã" else "Xác nhận", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy", color = Color.White) }
        }
    )
}

@Composable
private fun DirectTokenDialog(viewModel: LocketViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var uid by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E22),
        title = { Text("Nhập Firebase Token", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dành cho developer hoặc token trích xuất từ tài khoản Locket của bạn:", color = LocketTextDim, fontSize = 13.sp)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Firebase ID Token (Bearer)") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uid,
                    onValueChange = { uid = it },
                    label = { Text("User UID (tùy chọn)") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (token.isNotBlank()) {
                        viewModel.loginWithDirectToken(token.trim(), uid.trim().ifBlank { "user_me" }) {
                            Toast.makeText(context, "Đã lưu token & đang đồng bộ dữ liệu!", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LocketYellow)
            ) {
                Text("Lưu & Đồng bộ", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy", color = Color.White) }
        }
    )
}
