package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.theme.*

@Composable
fun LoginScreen(
    viewModel: LocketViewModel,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current

    var isPhoneMode by remember { mutableStateOf(false) }
    var showTokenInput by remember { mutableStateOf(false) }

    // Step: 1 = Enter identifier, 2 = Enter password / OTP
    var step by remember { mutableStateOf(1) }

    var emailInput by remember { mutableStateOf(viewModel.sessionManager.savedEmail ?: "") }
    var phoneInput by remember { mutableStateOf("+84") }
    var passwordInput by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }

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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: Circular Back button (Matches media_1795768737699_40ac5e46.png)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF262628))
                        .clickable {
                            if (step > 1) {
                                step = 1
                            } else {
                                onBack()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Quay lại",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(44.dp))

            // Main Title (Matches media_1795768737699_40ac5e46.png)
            val titleText = when {
                showTokenInput -> "Nhập Firebase ID Token"
                step == 2 && !isPhoneMode -> "Nhập mật khẩu của bạn"
                step == 2 && isPhoneMode -> "Nhập mã gồm 6 chữ số"
                isPhoneMode -> "Số điện thoại của bạn là gì?"
                else -> "Email của bạn là gì?"
            }

            Text(
                text = titleText,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(26.dp))

            // Input Field (Matches media_1795768737699_40ac5e46.png)
            if (showTokenInput) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder = { Text("Dán Bearer token...", color = LocketTextDim) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = LocketYellow,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color(0xFF1C1C1E),
                        unfocusedContainerColor = Color(0xFF1C1C1E)
                    ),
                    maxLines = 5
                )
            } else if (step == 1) {
                if (!isPhoneMode) {
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        placeholder = { Text("Địa chỉ email", color = Color(0xFF636366), fontSize = 16.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = LocketYellow,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color(0xFF1C1C1E),
                            unfocusedContainerColor = Color(0xFF1C1C1E)
                        ),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        placeholder = { Text("Số điện thoại (+84...)", color = Color(0xFF636366), fontSize = 16.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = LocketYellow,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color(0xFF1C1C1E),
                            unfocusedContainerColor = Color(0xFF1C1C1E)
                        ),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Toggle Pill Button (Matches media_1795768737699_40ac5e46.png)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable { isPhoneMode = !isPhoneMode }
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = if (!isPhoneMode) "Sử dụng số điện thoại" else "Sử dụng email",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                // Step 2: Password or OTP
                if (!isPhoneMode) {
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        placeholder = { Text("Mật khẩu", color = Color(0xFF636366), fontSize = 16.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = LocketYellow,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color(0xFF1C1C1E),
                            unfocusedContainerColor = Color(0xFF1C1C1E)
                        ),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = otpInput,
                        onValueChange = { otpInput = it },
                        placeholder = { Text("Mã xác thực gồm 6 số", color = Color(0xFF636366), fontSize = 16.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = LocketYellow,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color(0xFF1C1C1E),
                            unfocusedContainerColor = Color(0xFF1C1C1E)
                        ),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Legal Disclaimer Text (Matches media_1795768737699_40ac5e46.png)
            val annotatedLegalText = buildAnnotatedString {
                append("Bằng cách nhấn vào nút Tiếp tục, bạn đồng ý với chúng tôi ")
                withStyle(style = SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                    append("Điều khoản dịch vụ")
                }
                append(" và ")
                withStyle(style = SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                    append("Chính sách quyền riêng tư")
                }
            }

            Text(
                text = annotatedLegalText,
                color = Color(0xFF8E8E93),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 14.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            // "Tiếp tục ->" Button (Matches media_1795768737699_40ac5e46.png)
            val isValid = when {
                showTokenInput -> tokenInput.isNotBlank()
                step == 1 && !isPhoneMode -> emailInput.isNotBlank() && emailInput.contains("@")
                step == 1 && isPhoneMode -> phoneInput.length >= 9
                step == 2 && !isPhoneMode -> passwordInput.isNotBlank()
                step == 2 && isPhoneMode -> otpInput.length >= 6
                else -> false
            }

            Button(
                onClick = {
                    if (showTokenInput) {
                        viewModel.loginWithDirectToken(tokenInput.trim(), "user_me") {
                            Toast.makeText(context, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                            onLoginSuccess()
                        }
                    } else if (step == 1) {
                        if (!isPhoneMode) {
                            step = 2
                        } else {
                            isLoading = true
                            viewModel.sendPhoneOtp(
                                phone = phoneInput.trim(),
                                onSuccess = {
                                    isLoading = false
                                    step = 2
                                    Toast.makeText(context, "Đã gửi mã xác thực tới $phoneInput", Toast.LENGTH_SHORT).show()
                                },
                                onError = { err ->
                                    isLoading = false
                                    Toast.makeText(context, "Lỗi gửi mã: $err", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    } else {
                        // Step 2: Call real Locket API
                        isLoading = true
                        if (!isPhoneMode) {
                            viewModel.loginWithEmail(
                                email = emailInput.trim(),
                                pass = passwordInput,
                                onSuccess = {
                                    isLoading = false
                                    Toast.makeText(context, "Đăng nhập thành công! 💛", Toast.LENGTH_SHORT).show()
                                    onLoginSuccess()
                                },
                                onError = { err ->
                                    isLoading = false
                                    Toast.makeText(context, "Lỗi đăng nhập: $err", Toast.LENGTH_LONG).show()
                                }
                            )
                        } else {
                            viewModel.verifyPhoneOtp(
                                phone = phoneInput.trim(),
                                code = otpInput.trim(),
                                onSuccess = {
                                    isLoading = false
                                    Toast.makeText(context, "Đăng nhập thành công! 💛", Toast.LENGTH_SHORT).show()
                                    onLoginSuccess()
                                },
                                onError = { err ->
                                    isLoading = false
                                    Toast.makeText(context, "Lỗi OTP: $err", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }
                },
                enabled = isValid && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isValid) LocketYellow else Color(0xFF1C1C1E),
                    contentColor = if (isValid) Color.Black else Color(0xFF636366),
                    disabledContainerColor = Color(0xFF1C1C1E),
                    disabledContentColor = Color(0xFF636366)
                ),
                shape = RoundedCornerShape(29.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = if (isValid) Color.Black else Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Tiếp tục",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Direct Token Shortcut
            Text(
                text = if (!showTokenInput) "Hoặc dán Firebase ID Token trực tiếp" else "Quay lại đăng nhập thông thường",
                color = LocketTextDim,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { showTokenInput = !showTokenInput }
                    .padding(8.dp)
            )
        }
    }
}
