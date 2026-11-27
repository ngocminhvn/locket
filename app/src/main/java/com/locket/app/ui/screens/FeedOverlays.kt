package com.locket.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.locket.app.model.Friend
import com.locket.app.ui.theme.LocketYellow
import com.locket.app.ui.theme.LocalEmojiState
import com.locket.app.ui.theme.emojiStyled
import com.locket.app.ui.theme.emojiInputTransformation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

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
    val state = LocalEmojiState.current
    val groups = remember(state.catalog) { state.catalog?.entries?.map { it.group }?.distinct().orEmpty() }
    var selected by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var keyboardEmoji by remember { mutableStateOf("") }
    val options = remember(query, selected, state.catalog) {
        state.catalog?.entries.orEmpty().filter {
            if (query.isBlank()) it.group == groups.getOrNull(selected)
            else it.value.contains(query.trim()) || it.name.contains(query.trim(), ignoreCase = true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF242424), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), dragHandle = null) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
            if (state.catalog == null) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = LocketYellow)
                Text("Chọn emoji bằng bàn phím", color = Color.White)
                OutlinedTextField(keyboardEmoji, { keyboardEmoji = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Emoji") }, singleLine = true, visualTransformation = emojiInputTransformation())
                val valid = keyboardEmoji.isNotBlank() && keyboardEmoji.codePoints().anyMatch {
                    it >= 0x1F000 || it in 0x2600..0x27BF || it == 0xFE0F || it == 0x20E3
                } && android.icu.text.BreakIterator.getCharacterInstance().apply { setText(keyboardEmoji) }.let {
                    it.first(); it.next() == keyboardEmoji.length
                }
                TextButton(enabled = valid, onClick = { onPick(keyboardEmoji) }) { Text("Gửi emoji", color = LocketYellow) }
                if (state.error) TextButton(onClick = state.retry) { Text("Tải lại bộ emoji") }
            } else {
                TextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Tìm emoji") }, shape = CircleShape,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF383838),
                        unfocusedContainerColor = Color(0xFF383838), focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEachIndexed { index, group ->
                        val label = when (group) {
                            "Smileys & Emotion" -> "Cảm xúc"
                            "People & Body" -> "Con người"
                            "Animals & Nature" -> "Thiên nhiên"
                            "Food & Drink" -> "Đồ ăn"
                            "Travel & Places" -> "Du lịch"
                            "Activities" -> "Hoạt động"
                            "Objects" -> "Đồ vật"
                            "Symbols" -> "Biểu tượng"
                            "Flags" -> "Cờ"
                            "Component" -> "Thành phần"
                            else -> group
                        }
                        FilterChip(selected = selected == index && query.isBlank(), onClick = { selected = index; query = "" }, label = { Text(label) })
                    }
                }
                key(selected, query) {
                    LazyVerticalGrid(GridCells.Fixed(6), Modifier.fillMaxWidth().height(280.dp)) {
                        items(options, key = { it.value }) { entry ->
                            Box(Modifier.height(53.dp).clickable { onPick(entry.value) }, contentAlignment = Alignment.Center) {
                                Text(emojiStyled(entry.value), fontSize = 29.sp, lineHeight = 36.sp)
                            }
                        }
                    }
                }
                if (options.isEmpty()) Text("Không tìm thấy emoji", color = Color.LightGray)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = LocketYellow)
            }
        }
    }
}
