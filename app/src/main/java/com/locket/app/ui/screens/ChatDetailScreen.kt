package com.locket.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.locket.app.model.Friend
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.liquidGlassEffect
import com.locket.app.ui.theme.*

data class ChatMessage(
    val id: String,
    val senderName: String,
    val isMe: Boolean,
    val text: String,
    val momentImageUrl: String? = null,
    val timestamp: String
)

@Composable
fun ChatDetailScreen(
    viewModel: LocketViewModel,
    friend: Friend,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var inputMessage by remember { mutableStateOf("") }

    // Real chat messages and moments from live API data
    val friendMoments = remember(friend, viewModel.moments.collectAsState().value) {
        viewModel.moments.value.filter { it.userUid == friend.uid }
    }
    val messages = remember(friend) {
        val list = mutableStateListOf<ChatMessage>()
        for (m in friendMoments) {
            val dateStr = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault()).format(java.util.Date(m.timestampSeconds * 1000))
            list.add(
                ChatMessage(
                    id = m.id,
                    senderName = friend.displayName,
                    isMe = false,
                    text = m.caption,
                    momentImageUrl = m.thumbnailUrl,
                    timestamp = dateStr
                )
            )
        }
        list
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Bar: Back arrow, Avatar with yellow border, Friend name
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E)),
                    contentAlignment = Alignment.Center
                ) {
                    if (friend.avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = friend.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = friend.displayName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Message Stream (Matches media_1795768733273_bbe2a73c.jpg)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(messages) { msg ->
                    if (msg.momentImageUrl != null) {
                        // Shared Moment Card in Chat
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = msg.timestamp,
                                color = LocketTextDim,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.0f)
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(Color(0xFF1E1E1E))
                            ) {
                                AsyncImage(
                                    model = msg.momentImageUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )

                                // Overlay tag: Friend avatar + "Vui thì cười 1w"
                                Row(
                                    modifier = Modifier
                                        .padding(14.dp)
                                        .liquidGlassEffect(RoundedCornerShape(16.dp), backgroundColor = Color(0x99000000))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .align(Alignment.TopStart),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color.Gray)
                                    )
                                    Text(
                                        text = "${friend.displayName} 1w",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else if (msg.isMe) {
                        // Sent message bubble: White rounded pill on the right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White)
                                    .padding(horizontal = 18.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = Color.Black,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        // Received message bubble: Avatar on left + dark gray pill
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2C2C2E)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF2C2C2E))
                                    .padding(horizontal = 18.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Reaction & Message Bar (Matches media_1795768733273_bbe2a73c.jpg)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .liquidGlassEffect(RoundedCornerShape(28.dp), backgroundColor = Color(0xD9222224))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputMessage,
                    onValueChange = { inputMessage = it },
                    placeholder = { Text("Gửi tin nhắn...", color = Color(0xFF8E8E93), fontSize = 15.sp) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )

                val emojis = listOf("🔥", "😍", "💛")
                for (emoji in emojis) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                viewModel.reactToMoment("m_current", friend.uid, emoji)
                                Toast.makeText(context, "Đã gửi $emoji", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 20.sp)
                    }
                }

                if (inputMessage.isNotBlank()) {
                    Text(
                        text = "Gửi",
                        color = LocketYellow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                viewModel.sendChatMessage(friend.uid, inputMessage)
                                messages.add(
                                    ChatMessage(
                                        id = "local_${System.currentTimeMillis()}",
                                        senderName = "Tôi",
                                        isMe = true,
                                        text = inputMessage,
                                        timestamp = "Vừa xong"
                                    )
                                )
                                inputMessage = ""
                            }
                            .padding(8.dp)
                    )
                } else {
                    IconButton(
                        onClick = { Toast.makeText(context, "Biểu cảm", Toast.LENGTH_SHORT).show() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEmotions,
                            contentDescription = "Emoji",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
