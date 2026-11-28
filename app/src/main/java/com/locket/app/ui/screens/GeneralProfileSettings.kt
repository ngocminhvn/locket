package com.locket.app.ui.screens

import com.locket.app.ui.theme.*

import android.content.Intent
import android.provider.Settings
import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locket.app.api.LocketApiClient
import com.locket.app.ui.LocketViewModel
import kotlinx.coroutines.launch
import java.time.MonthDay

@Composable
internal fun GeneralProfileSettings(viewModel: LocketViewModel, displayName: String, onEditName: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by viewModel.currentUser.collectAsState()
    var details by remember(user.uid) { mutableStateOf(viewModel.cachedGeneralAccount()) }
    var loading by remember(user.uid) { mutableStateOf(true) }
    var readError by remember(user.uid) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        readError = null
        try {
            viewModel.readGeneralAccount().onSuccess { details = it }
                .onFailure { readError = it.localizedMessage ?: "Không tải được thông tin tài khoản" }
        } finally { loading = false }
    }
    LaunchedEffect(user.uid) { if (user.uid.isNotBlank()) refresh() else loading = false }

    fun open(kind: String) {
        editing = kind
        error = null
        code = ""
        codeSent = false
        draft = when (kind) {
            "email" -> details?.email.orEmpty()
            "phone" -> details?.phone.orEmpty()
            else -> ""
        }
        val birthday = details?.birthdayMdd
        month = birthday?.let { (it / 100).toString() }.orEmpty()
        day = birthday?.let { (it % 100).toString() }.orEmpty()
    }
    fun shown(value: String?): String = value?.takeIf { it.isNotBlank() }
        ?: if (details == null && loading) "Đang tải..." else if (details == null && readError != null) "Không tải được" else "Chưa có thông tin"

    GeneralRow(Icons.Default.Notifications, "Thông báo") {
        try {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } catch (e: Exception) { notice = "Không mở được cài đặt thông báo" }
    }
    GeneralRow(Icons.Default.AccountBox, "Đổi tên", displayName.takeIf { it.isNotBlank() }, onEditName)
    GeneralRow(Icons.Default.Phone, "Thay đổi số điện thoại", shown(details?.phone)) { open("phone") }
    GeneralRow(Icons.Default.Email, "Thay đổi địa chỉ email", shown(details?.email)) { open("email") }
    val birthdayLabel = details?.birthdayMdd?.let {
        runCatching { MonthDay.of(it / 100, it % 100) }.getOrNull()
    }?.let { "Tháng ${it.monthValue} ${it.dayOfMonth}" }
    GeneralRow(Icons.Default.Cake, "Edit birthday", shown(birthdayLabel)) { open("birthday") }
    if (readError != null) {
        TextButton(onClick = { scope.launch { refresh() } }, enabled = !loading) { Text("Tải lại thông tin", color = LocketYellow) }
    }

    editing?.let { kind ->
        val title = when (kind) {
            "email" -> "Thay đổi địa chỉ email"
            "phone" -> "Thay đổi số điện thoại"
            else -> "Chỉnh sửa sinh nhật"
        }
        val valid = when (kind) {
            "email" -> Patterns.EMAIL_ADDRESS.matcher(draft.trim()).matches() && draft.trim() != details?.email
            "phone" -> Regex("""\+[1-9]\d{7,14}""").matches(draft.trim()) && draft.trim() != details?.phone &&
                (!codeSent || Regex("""\d{4,8}""").matches(code))
            else -> runCatching { MonthDay.of(month.toInt(), day.toInt()) }.isSuccess
        }
        AlertDialog(
            onDismissRequest = { if (!busy) editing = null },
            containerColor = LocketModalBg,
            title = { Text(title, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (kind == "birthday") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(month, { month = it.filter(Char::isDigit).take(2) }, label = { Text("Tháng") },
                                modifier = Modifier.weight(1f), enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, label = { Text("Ngày") },
                                modifier = Modifier.weight(1f), enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    } else {
                        OutlinedTextField(draft, { draft = it }, label = { Text(if (kind == "email") "Email mới" else "Số điện thoại (+84...)") },
                            enabled = !busy && !codeSent, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = if (kind == "email") KeyboardType.Email else KeyboardType.Phone))
                        if (kind == "phone" && codeSent) OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) },
                            label = { Text("Mã xác nhận SMS") }, enabled = !busy, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = LocketRed, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = valid && !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val sendingCode = kind == "phone" && !codeSent
                            val result = when {
                                kind == "email" -> viewModel.changeGeneralEmail(draft.trim())
                                sendingCode -> viewModel.requestGeneralPhoneCode(draft.trim())
                                kind == "phone" -> viewModel.changeGeneralPhone(draft.trim(), code)
                                else -> viewModel.changeGeneralBirthday(month.toInt(), day.toInt())
                            }
                            result.onSuccess {
                                if (sendingCode) codeSent = true
                                else {
                                    editing = null
                                    refresh()
                                    notice = "Máy chủ đã xác nhận thay đổi."
                                }
                            }.onFailure { error = it.localizedMessage ?: "Không cập nhật được" }
                        } finally { busy = false }
                    }
                }) { Text(if (kind == "phone" && !codeSent) "Gửi mã" else "Xác nhận", color = LocketYellow) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { editing = null }) { Text("Hủy") } }
        )
    }
    notice?.let { value ->
        AlertDialog(onDismissRequest = { notice = null }, title = { Text("Tổng quát") }, text = { Text(value) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } })
    }
}

@Composable
private fun GeneralRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    ProfileSettingsRow(
        icon = { Icon(icon, null, tint = Color(0xFFCCCCCC), modifier = Modifier.size(24.dp)) },
        title = title, subtitle = subtitle, onClick = onClick
    )
}
