package com.locket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locket.app.model.Friend
import com.locket.app.storage.SessionManager
import com.locket.app.ui.components.SkeletonAsyncImage
import com.locket.app.ui.theme.*

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider != ComponentName(this, LocketWidgetProvider::class.java)) {
            finish()
            return
        }
        val session = SessionManager(this)
        val owner = session.currentUser.uid
        val loggedIn = session.isLoggedIn && owner.isNotBlank()
        val friends = if (loggedIn) session.getFriends().filter { it.uid.isNotBlank() && it.uid != owner }
            .distinctBy { it.uid }.sortedBy { it.displayName.ifBlank { it.username } } else emptyList()
        val choose: (String) -> Unit = { uid ->
            if (session.isLoggedIn && session.currentUser.uid == owner) {
                session.writeAux(owner, "widget_friend:$id", uid)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                LocketWidgetUpdater.updateAll(this)
                WidgetRefreshJob.schedule(this, immediate = true)
            }
            finish()
        }
        setContent {
            LocketTheme {
                Surface(Modifier.fillMaxSize(), color = LocketBlack) {
                    if (loggedIn) WidgetFriendPicker(friends, { finish() }, choose)
                    else Column(Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Hãy đăng nhập Locket trước khi thêm widget.", color = Color.White)
                        Button(onClick = { finish() }) { Text("OK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetFriendPicker(friends: List<Friend>, onBack: () -> Unit, onChoose: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(friends, query) {
        val term = query.trim()
        friends.filter { it.displayName.contains(term, ignoreCase = true) || it.username.contains(term, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = Color.White)
            }
            Text("Chọn bạn cho widget", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            placeholder = { Text("Tìm bạn bè", color = LocketTextDim) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = LocketTextDim) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LocketSearchBg,
                unfocusedContainerColor = LocketSearchBg,
                focusedBorderColor = LocketYellow,
                unfocusedBorderColor = Color.Transparent
            )
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            if (query.isBlank() || "Tất cả bạn bè".contains(query.trim(), ignoreCase = true)) {
                item(key = "all") { WidgetFriendRow(null) { onChoose("") } }
            }
            items(filtered, key = { "friend:" + it.uid }) { friend ->
                WidgetFriendRow(friend) { onChoose(friend.uid) }
            }
            if (filtered.isEmpty() && query.isNotBlank() && !"Tất cả bạn bè".contains(query.trim(), ignoreCase = true)) {
                item { Text("Không tìm thấy bạn bè", color = LocketTextDim, modifier = Modifier.padding(16.dp)) }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("Hủy", color = LocketTextDim, fontSize = 16.sp)
        }
    }
}

@Composable
private fun WidgetFriendRow(friend: Friend?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(LocketSheetBg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape)
            .background(if (friend == null) LocketYellow else LocketAvatarBg),
            contentAlignment = Alignment.Center) {
            if (friend != null && friend.avatarUrl.isNotBlank()) {
                SkeletonAsyncImage(friend.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(if (friend == null) Icons.Default.People else Icons.Default.Person, null,
                    tint = if (friend == null) LocketOnYellow else Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(friend?.let { it.displayName.ifBlank { it.username }.ifBlank { it.uid } } ?: "Tất cả bạn bè",
            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
