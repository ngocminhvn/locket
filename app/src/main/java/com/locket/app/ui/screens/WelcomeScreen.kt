package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.ui.theme.LocketBlack
import com.locket.app.ui.theme.LocketYellow

@Composable
fun WelcomeScreen(
    onNavigateToLogin: () -> Unit
) {
    val context = LocalContext.current

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
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.5f))

            // Phone Mockup Graphic with Widgets (Matches media_1795768729287_9b00558c.jpg)
            Box(
                modifier = Modifier
                    .width(196.dp)
                    .height(352.dp)
                    .clip(RoundedCornerShape(42.dp))
                    .background(Color(0xFF161618))
                    .border(5.5.dp, Color(0xFF48484A), RoundedCornerShape(42.dp))
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 2x2 Photo Widget with yellow border
                            Box(
                                modifier = Modifier
                                    .size(78.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF2C2C2E))
                                    .border(2.5.dp, LocketYellow, RoundedCornerShape(22.dp))
                            ) {
                                AsyncImage(
                                    model = "https://picsum.photos/seed/locketwidget/300/300",
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }

                            // 4 Small App Icons
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallAppIcon()
                                    SmallAppIcon()
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallAppIcon()
                                    SmallAppIcon()
                                }
                            }
                        }

                        // App rows
                        for (r in 0 until 3) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                for (c in 0 until 4) {
                                    SmallAppIcon()
                                }
                            }
                        }
                    }

                    // Bottom Dock inside phone mockup
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF2C2C2E))
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Logo & App Name: 💛 Locket (Matches media_1795768729287_9b00558c.jpg)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocketYellow),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "💛",
                        fontSize = 24.sp
                    )
                }
                Text(
                    text = "Locket",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tagline: "Ảnh trực tiếp từ bạn bè,\nngay trên màn hình chính"
            Text(
                text = "Ảnh trực tiếp từ bạn bè,\nngay trên màn hình chính",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 26.sp
            )

            Spacer(modifier = Modifier.weight(0.8f))

            // "Tạo một tài khoản" Button -> DISABLED THEO YÊU CẦU CỦA USER
            Button(
                onClick = {
                    Toast.makeText(context, "Tính năng đăng ký tạm đóng, vui lòng Đăng nhập tài khoản Locket", Toast.LENGTH_SHORT).show()
                },
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = Color(0x66FFC800),
                    disabledContentColor = Color(0x66000000)
                ),
                shape = RoundedCornerShape(29.dp)
            ) {
                Text(
                    text = "Tạo một tài khoản",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // "Đăng nhập" Button (Active)
            Text(
                text = "Đăng nhập",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onNavigateToLogin)
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SmallAppIcon() {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2C2C2E))
    )
}
