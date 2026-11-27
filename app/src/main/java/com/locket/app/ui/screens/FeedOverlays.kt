package com.locket.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.locket.app.model.Friend
import com.locket.app.ui.theme.LocketYellow

@Composable
internal fun BoxScope.FeedFriendPopup(modifier: Modifier, friends: List<Friend>, selectedFriend: Friend?,
    onSelectFriend: (Friend?) -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.50f)).clickable(onClick = onDismiss))
    androidx.activity.compose.BackHandler { onDismiss() }
    LazyColumn(modifier.fillMaxWidth(0.74f).heightIn(max = 302.dp)
        .clip(RoundedCornerShape(24.dp)).background(Color(0xF2333331))) {
        item {
            FilterFriendRow(null, selectedFriend == null) { onSelectFriend(null); onDismiss() }
        }
        items(friends, key = { it.uid }) { friend ->
            HorizontalDivider(color = Color.Black.copy(alpha = 0.25f), thickness = 0.5.dp)
            FilterFriendRow(friend, selectedFriend?.uid == friend.uid) { onSelectFriend(friend); onDismiss() }
        }
    }
}

@Composable
private fun FilterFriendRow(friend: Friend?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 55.dp).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF50504D)), contentAlignment = Alignment.Center) {
            if (friend != null && friend.avatarUrl.isNotBlank())
                AsyncImage(friend.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (friend == null) Icons.Default.People else Icons.Default.Person, null, tint = Color.LightGray, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(friend?.displayName?.ifBlank { friend.username } ?: "Tất cả bạn bè", color = Color.White,
            modifier = Modifier.weight(1f), style = profileTextStyle(16.sp, 20.sp, FontWeight.Bold), maxLines = 1)
        Icon(if (selected) Icons.Default.Check else Icons.Default.ChevronRight, null,
            tint = if (selected) LocketYellow else Color.LightGray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomentEmojiSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val pick by rememberUpdatedState(onPick)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF242424),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Chọn biểu cảm", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(340.dp),
                factory = { context ->
                    EmojiPickerView(context).apply {
                        emojiGridColumns = 8
                        setOnEmojiPickedListener { item -> pick(item.emoji) }
                    }
                }
            )
        }
    }
}
