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

private data class EmojiGroup(val label: String, val symbol: String, val entries: String)
private val emojiGroups = listOf(
    EmojiGroup("Mặt cười & cảm xúc", "😀", "😀|grin smile cười 😃|happy 😄|smile 😁|grin 😆|laugh 😅|sweat 🤣|rolling laugh 😂|tears joy cười 🙂|smile 🙃|upside down 😉|wink 😊|blush 😇|angel 🥰|love 😍|heart eyes 🤩|star 😘|kiss 😗|kiss ☺️|smile 😚|kiss 😙|kiss 🥲|tear 😋|yum 😛|tongue 😜|wink 🤪|crazy 😝|tongue 🤑|money 🤗|hug 🤭|giggle 🫢|surprise 🤫|quiet 🤔|think 🫡|salute 🤐|zip 🤨|sus 😐|neutral 😑|blank 😶|silent 🙄|roll 😏|smirk 😒|annoyed 😔|sad 😢|cry khóc 😭|cry 😤|angry 😡|angry 🤬|mad 😳|embarrassed 🥺|plead 🥹|tear 😱|shock 😴|sleep 🤯|mind blown 😎|cool 🤓|nerd 🤡|clown 💀|skull 👻|ghost 💩|poop 💛|heart vàng ❤️|heart yêu 🩷|pink heart 🧡|orange heart 💚|green heart 💙|blue heart 💜|purple heart 🖤|black heart 🤍|white heart 💔|broken heart 💕|love 💖|sparkle heart 🔥|fire lửa ✨|sparkles"),
    EmojiGroup("Người & cử chỉ", "👋", "👋|wave 👍|like thích 👎|dislike 👌|okay ✌️|peace 🤞|luck 🤟|love 🤘|rock 🤙|call 👈|left 👉|right 👆|up 👇|down ☝️|point ✋|hand 🖐️|hand 🙌|celebrate 👏|clap 🤝|shake 🙏|pray cảm ơn 💪|strong 🫶|heart hands 🤍|heart"),
    EmojiGroup("Động vật & thiên nhiên", "🐱", "🐶|dog chó 🐱|cat mèo 🐭|mouse 🐹|hamster 🐰|rabbit 🦊|fox 🐻|bear 🐼|panda 🐨|koala 🐯|tiger 🦁|lion 🐮|cow 🐷|pig 🐸|frog 🐵|monkey 🐔|chicken 🐧|penguin 🐦|bird 🦋|butterfly 🐝|bee 🌸|flower hoa 🌹|rose 🌻|sunflower 🌷|tulip 🌱|plant 🌴|palm 🍀|clover 🌈|rainbow ☀️|sun 🌙|moon ⭐|star"),
    EmojiGroup("Đồ ăn & thức uống", "🍕", "🍎|apple 🍓|strawberry 🍉|watermelon 🍌|banana 🍇|grape 🥑|avocado 🍔|burger 🍟|fries 🍕|pizza 🌭|hotdog 🍜|noodles 🍣|sushi 🍚|rice 🍦|ice cream 🍰|cake 🎂|birthday 🍫|chocolate 🍩|donut ☕|coffee 🧋|tea 🍺|beer"),
    EmojiGroup("Du lịch", "✈️", "🚗|car 🚕|taxi 🚌|bus 🚲|bike 🏍️|motorbike 🚆|train ✈️|plane 🚀|rocket 🚢|ship 🏠|home 🏖️|beach 🏔️|mountain 🏕️|camp 🌅|sunrise 🌃|night"),
    EmojiGroup("Hoạt động", "⚽", "⚽|soccer 🏀|basketball 🏐|volleyball 🎾|tennis 🏸|badminton 🏓|ping pong 🏆|trophy 🥇|gold 🎮|game 🎲|dice 🎸|guitar 🎤|mic 🎧|music 🎨|art"),
    EmojiGroup("Đồ vật", "💡", "📱|phone 📷|camera 🎥|video 💻|computer 💡|light 🎁|gift 🎈|balloon 🎉|party 🎊|celebration 💌|letter 💎|diamond 🔑|key 🛍️|shopping 📚|books ✏️|pencil"),
    EmojiGroup("Biểu tượng", "✨", "✅|check ❌|cross ❗|warning ❓|question 💯|hundred 💥|boom 💫|dizzy ✨|sparkles 🔥|fire ⚡|lightning 🎵|music ♻️|recycle ☮️|peace ♡|heart"),
    EmojiGroup("Cờ", "🏳️", "🇻🇳|vietnam việt nam 🇺🇸|usa 🇯🇵|japan 🇰🇷|korea 🇨🇳|china 🇬🇧|uk 🇫🇷|france 🇩🇪|germany 🇹🇭|thailand 🇸🇬|singapore 🏳️|white flag 🏳️‍🌈|rainbow flag")
)
// The emoji plus its keywords are encoded with a separator before each next emoji;
// split by a whitespace followed by a non-ASCII emoji and a pipe.
private fun EmojiGroup.options(): List<Pair<String, String>> =
    entries.split(Regex("""\s+(?=[^\p{L}\p{N}\s|]+\|)""")).mapNotNull {
        val emoji = it.substringBefore('|')
        if ('|' !in it) null else emoji to it.substringAfter('|')
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomentEmojiSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val options = remember(query, selected) {
        if (query.isBlank()) emojiGroups[selected].options()
        else emojiGroups.flatMap { it.options() }.distinctBy { it.first }.filter {
            it.first.contains(query.trim()) || it.second.contains(query.trim(), ignoreCase = true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF242424), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), dragHandle = null) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            TextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Tìm emoji") }, shape = CircleShape,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF383838),
                    unfocusedContainerColor = Color(0xFF383838), focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            Spacer(Modifier.height(12.dp))
            Text(if (query.isBlank()) emojiGroups[selected].label else "Kết quả tìm kiếm", color = Color.LightGray,
                style = profileTextStyle(14.sp, 18.sp, FontWeight.Medium))
            LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxWidth().height(240.dp)) {
                items(options) { entry ->
                    Box(Modifier.height(58.dp).clickable { onPick(entry.first) }, contentAlignment = Alignment.Center) {
                        Text(entry.first, fontSize = 32.sp, lineHeight = 39.sp)
                    }
                }
            }
            if (options.isEmpty()) Text("Không tìm thấy emoji", color = Color.LightGray)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF393939)).padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                emojiGroups.forEachIndexed { index, group ->
                    Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (index == selected && query.isBlank()) Color(0xFF606060) else Color.Transparent)
                        .clickable { selected = index; query = "" }, contentAlignment = Alignment.Center) {
                        Text(group.symbol, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
