package com.locket.app.ui.screens

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.emoji2.emojipicker.R as EmojiPickerR
import kotlin.math.abs
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF242424),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        // Keep the sheet anchors stable, but size the picker to the visible part
        // of the sheet so the last emoji row is reachable in both positions.
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
            val contentHeight = if (sheetState.targetValue == SheetValue.PartiallyExpanded) {
                Modifier.height((screenHeight * 0.5f - 80.dp).coerceAtLeast(120.dp))
            } else {
                Modifier.fillMaxHeight()
            }
            Column(Modifier.fillMaxWidth().then(contentHeight)
                .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Chọn biểu cảm", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        val picker = EmojiPickerView(context).apply {
                            emojiGridColumns = 8
                            setOnEmojiPickedListener { item -> pick(item.emoji) }
                        }
                        SwipeEmojiPickerHost(context, picker)
                    }
                )
            }
        }
    }
}

/**
 * AndroidX supplies the system emoji, variants, recents and category tabs.
 * Keep its vertical RecyclerView gestures local to the picker; the sheet
 * is still draggable by its handle and title. Horizontal swipes reuse
 * the existing category click action rather than maintaining a second list.
 */
private class SwipeEmojiPickerHost(
    context: Context,
    private val picker: EmojiPickerView
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeDistance = 32f * resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var horizontal = false
    private var vertical = false

    init {
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // RecyclerView may release interception when it reaches an edge.
        // Do not hand that same gesture to the sheet or the outer pager.
        super.requestDisallowInterceptTouchEvent(disallowIntercept || tracking)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                horizontal = false
                vertical = false
                startX = event.x
                startY = event.y
                // AndroidX creates its body asynchronously; resolve it when
                // the user touches the picker, not just during construction.
                picker.findViewById<View>(EmojiPickerR.id.emoji_picker_body)
                    ?.isNestedScrollingEnabled = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!horizontal) vertical = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && !horizontal && !vertical && event.pointerCount == 1) {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (dx > touchSlop && dx > dy * 1.25f &&
                        event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) {
                        horizontal = true
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    } else if (dy > touchSlop || dx > touchSlop * 2) {
                        vertical = true
                    }
                }
                if (horizontal) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasHorizontal = horizontal
                if (wasHorizontal && event.actionMasked == MotionEvent.ACTION_UP) {
                    val dx = event.x - startX
                    if (abs(dx) >= swipeDistance) switchCategory(if (dx < 0) 1 else -1)
                }
                val handled = if (wasHorizontal) true else super.dispatchTouchEvent(event)
                tracking = false
                horizontal = false
                vertical = false
                super.requestDisallowInterceptTouchEvent(false)
                return handled
            }
        }
        if (horizontal) return true
        val handled = super.dispatchTouchEvent(event)
        if (tracking) parent?.requestDisallowInterceptTouchEvent(true)
        return handled || tracking
    }

    private fun switchCategory(step: Int) {
        // AndroidX lays out every category icon in its fixed-width header.
        // These resource IDs are scoped to the pinned EmojiPicker dependency.
        val header = picker.findViewById<ViewGroup>(EmojiPickerR.id.emoji_picker_header) ?: return
        val tabs = (0 until header.childCount).map { header.getChildAt(it) }.sortedBy { it.left }
        val selected = tabs.indexOfFirst {
            it.findViewById<View>(EmojiPickerR.id.emoji_picker_header_icon)?.isSelected == true
        }
        if (selected < 0) return
        tabs.getOrNull(selected + step)?.performClick()
    }
}
