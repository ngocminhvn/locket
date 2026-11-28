package com.locket.app.ui.screens

import com.locket.app.ui.theme.*

import android.content.Context
import android.graphics.Paint
import android.widget.TextView
import android.text.TextUtils
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.emoji2.emojipicker.RecentEmojiProvider
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.roundToInt
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
import com.locket.app.ui.components.SkeletonAsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.locket.app.model.Friend

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
                SkeletonAsyncImage(friend.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
    val context = LocalContext.current
    val pick by rememberUpdatedState(onPick)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val density = LocalDensity.current
    var visibleHeightPx by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val recent = remember(context) { SharedEmojiRecents(context) }
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val results by produceState<List<String>?>(initialValue = null, query, context) {
        value = null
        if (query.isNotBlank()) {
            value = withContext(Dispatchers.IO) { EmojiSearchIndex.search(context, query) }
        }
    }
    val headerHeight = dimensionResource(EmojiPickerR.dimen.emoji_picker_header_height)
    val emojiColumns = 8
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LocketSheetBg,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        // Apply bottom safe-area padding to the visible content, not to the
        // off-screen bottom edge of the partially expanded sheet.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = {
            Box(Modifier.padding(vertical = 9.dp).size(width = 32.dp, height = 4.dp)
                .clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
        }
    ) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun clearSearch() {
            query = ""
            focus.clearFocus()
            keyboard?.hide()
        }
        // Keep a stable measured sheet height for Material's drag anchors.
        // Measure the actual visible viewport after the sheet is positioned;
        // screenHeightDp excludes system bars on some Android versions and a
        // fixed half-screen estimate leaves a blank strip under the picker.
        BoxWithConstraints(
            Modifier.fillMaxWidth().fillMaxHeight(0.82f).clipToBounds()
                .onGloballyPositioned { coordinates ->
                    val rootHeight = coordinates.findRootCoordinates().size.height
                    val top = coordinates.positionInRoot().y
                    if (top.isFinite()) {
                        val height = (rootHeight - top).roundToInt().coerceIn(0, coordinates.size.height)
                        if (visibleHeightPx != height) visibleHeightPx = height
                    }
                }
        ) {
            val viewport = if (visibleHeightPx >= 0) {
                with(density) { visibleHeightPx.toDp() }.coerceIn(0.dp, maxHeight)
            } else maxHeight
            Column(Modifier.fillMaxWidth().height(viewport)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                        .height(40.dp).clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.09f)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.weight(1f).onFocusChanged {
                            if (it.isFocused) scope.launch { sheetState.expand() }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) Text("Tìm kiếm emoji", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                                inner()
                            }
                        }
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, "Xóa tìm kiếm", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val picker = EmojiPickerView(ctx).apply {
                                emojiGridColumns = emojiColumns
                                setRecentEmojiProvider(recent)
                                setOnEmojiPickedListener { item -> pick(item.emoji) }
                            }
                            SwipeEmojiPickerHost(ctx, picker)
                        },
                        update = { host -> host.setSearchMode(searching) { clearSearch() } }
                    )
                    if (searching) {
                        Box(Modifier.fillMaxSize().padding(top = headerHeight).background(LocketSheetBg)) {
                            val found = results
                            when {
                                found == null -> CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                                    color = Color.LightGray, strokeWidth = 2.dp
                                )
                                found.isEmpty() -> Text("Không tìm thấy emoji",
                                    modifier = Modifier.align(Alignment.Center), color = Color.LightGray, fontSize = 13.sp)
                                else -> LazyVerticalGrid(
                                    columns = GridCells.Fixed(emojiColumns),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(found, key = { it }) { emoji ->
                                        Box(Modifier.aspectRatio(1f).clickable {
                                            recent.recordSelection(emoji)
                                            pick(emoji)
                                        }, contentAlignment = Alignment.Center) {
                                            Text(emoji, fontSize = 24.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Real Unicode/CLDR search metadata only; all glyphs are drawn by the system. */
private object EmojiSearchIndex {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")
    private fun normalize(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(marks, "").replace('đ', 'd')

    @Volatile private var entries: List<Pair<String, String>>? = null

    fun search(context: Context, query: String): List<String> {
        val list = entries ?: synchronized(this) {
            entries ?: context.assets.open("emoji-search.tsv").bufferedReader().useLines { lines ->
                lines.filter { !it.startsWith("#") }.mapNotNull { line ->
                    val split = line.indexOf('\t')
                    if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
                }.toList()
            }.also { entries = it }
        }
        val raw = query.trim()
        val words = normalize(raw).split(spaces).filter { it.isNotBlank() }
        val paint = Paint()
        return list.asSequence().filter { (emoji, keywords) ->
            emoji.contains(raw) || (words.isNotEmpty() && words.all { keywords.contains(it) })
        }.map { it.first }.distinct().filter { paint.hasGlyph(it) }.toList()
    }
}

/** Reuse AndroidX's existing recents storage so search and browsing share history. */
private class SharedEmojiRecents(context: Context) : RecentEmojiProvider {
    private val prefs = context.getSharedPreferences("androidx.emoji2.emojipicker.preferences", Context.MODE_PRIVATE)
    private val key = "pref_key_recent_emoji"
    @Synchronized override fun recordSelection(emoji: String) {
        val recent = listOf(emoji) + read().filterNot { it == emoji }
        prefs.edit().putString(key, recent.take(100).joinToString(",")).apply()
    }
    private fun read() = prefs.getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }
    override suspend fun getRecentEmojiList(): List<String> = read()
}

/**
 * AndroidX supplies the system emoji, variants, recents and category tabs.
 * Keep its vertical RecyclerView gestures local to the picker; the sheet
 * is still draggable by its handle. Horizontal swipes reuse
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
    private var searching = false
    private var onHeaderTap: () -> Unit = {}
    private var styledBody: RecyclerView? = null

    init {
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setSearchMode(active: Boolean, onHeaderTap: () -> Unit) {
        this.onHeaderTap = onHeaderTap
        searching = active
        configureBody()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        configureBody()
    }

    private fun configureBody() {
        val body = picker.findViewById<RecyclerView>(EmojiPickerR.id.emoji_picker_body) ?: return
        val bodyVisibility = if (searching) View.INVISIBLE else View.VISIBLE
        if (body.visibility != bodyVisibility) body.visibility = bodyVisibility
        body.isNestedScrollingEnabled = false
        if (styledBody !== body) {
            styledBody = body
            fun style(child: View) {
                child.findViewById<TextView>(EmojiPickerR.id.category_name)?.apply {
                    // Category names are already available on the icon tabs.
                    // Keep only the recent section label and a one-pixel
                    // scroll anchor for each other group.
                    val isRecent = text.toString() == context.getString(EmojiPickerR.string.emoji_category_recent)
                    this.visibility = if (isRecent) View.VISIBLE else View.GONE
                    val rowHeight = if (isRecent) ViewGroup.LayoutParams.WRAP_CONTENT else 1
                    if (child.layoutParams.height != rowHeight) {
                        child.layoutParams = child.layoutParams.apply { height = rowHeight }
                    }
                    textSize = 13f
                    letterSpacing = 0f
                    isAllCaps = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                child.findViewById<TextView>(EmojiPickerR.id.emoji_picker_empty_category_view)?.apply {
                    textSize = 12f
                    letterSpacing = 0f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
            for (i in 0 until body.childCount) style(body.getChildAt(i))
            body.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) = style(view)
                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
        }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // RecyclerView may release interception when it reaches an edge.
        // Do not hand that same gesture to the sheet or the outer pager.
        super.requestDisallowInterceptTouchEvent(disallowIntercept || tracking)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val header = picker.findViewById<View>(EmojiPickerR.id.emoji_picker_header)
                if (searching && header != null && event.y <= header.bottom) onHeaderTap()
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
