package com.locket.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.RemoteViews
import coil.imageLoader
import coil.request.ImageRequest
import com.locket.app.MainActivity
import com.locket.app.R
import com.locket.app.model.Moment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Renders the persisted latest moment and supports the legacy cache on upgrade.
 * Anh thumbnail tai that bang Coil ra Bitmap; that bai thi giu khung hinh, khong dung anh gia.
 */
object LocketWidgetUpdater {

    private val renderLock = kotlinx.coroutines.sync.Mutex()

    fun updateAll(context: Context): kotlinx.coroutines.Job =
        CoroutineScope(Dispatchers.IO).launch { renderAll(context.applicationContext, false) }

    internal suspend fun refreshAll(context: Context): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) { renderAll(context.applicationContext, true) }

    private suspend fun renderAll(context: Context, network: Boolean): Boolean = renderLock.withLock {
        val app = context.applicationContext as com.locket.app.LocketApplication
        val session = app.sessionManager
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, LocketWidgetProvider::class.java))
        if (ids.isEmpty()) return@withLock true
        val owner = session.currentUser.uid.takeIf { session.isLoggedIn }.orEmpty()
        fun active() = owner.isNotBlank() && session.isLoggedIn && session.currentUser.uid == owner && !session.roomCacheClearPending
        val api = if (network && active()) com.locket.app.api.LocketApiClient(
            getToken = { session.idToken.takeIf { active() } },
            getUid = { owner.takeIf { active() } },
            getRefreshToken = { session.refreshToken.takeIf { active() } },
            isTokenStale = { session.isTokenStale },
            onTokensRefreshed = { token, refresh -> if (active()) {
                session.idToken = token
                session.refreshToken = refresh
            } }
        ) else null
        val friends = if (active()) session.getFriends().associateBy { it.uid } else emptyMap()
        val fetched = mutableMapOf<String, Result<Moment?>>()
        var success = true
        for (id in ids) {
            val selection = session.readAux(owner, "widget_friend:$id").orEmpty()
            val friend = friends[selection]
            val local = when {
                !active() -> null
                selection.isEmpty() -> session.getWidgetMoment(owner)
                friend != null -> app.cache.latestMomentByFriend(selection)
                else -> null
            }
            val latest = if (api != null && (selection.isEmpty() || friend != null)) {
                val result = fetched[selection] ?: (if (selection.isEmpty()) {
                    api.fetchMomentsFeed(null).map { page -> page.moments.maxByOrNull { it.timestampSeconds } }
                } else {
                    api.fetchHistoryPage(friendUid = selection).map { page ->
                        page.moments.filter { it.userUid == selection }.maxByOrNull { it.timestampSeconds }
                    }
                }).also { fetched[selection] = it }
                if (result.isFailure) {
                    success = false
                    continue // retain the existing widget on network errors
                }
                result.getOrNull() ?: if (selection.isEmpty()) local else null
            } else local
            val named = latest?.let { moment ->
                friends[moment.userUid]?.let { f ->
                    moment.copy(userName = f.displayName.ifBlank { f.username }.ifBlank { moment.userName })
                } ?: moment
            }
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150).takeIf { it > 0 } ?: 150
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150).takeIf { it > 0 } ?: 150
            val scale = (minOf(width, height) / 150f).coerceIn(0.75f, 2.2f)
            val avatarUrl = named?.let { friends[it.userUid]?.avatarUrl.orEmpty().ifBlank { it.userAvatarUrl } }.orEmpty()
            val views = buildViews(context, named, scale, width, avatarUrl)
            if (session.currentUser.uid.takeIf { session.isLoggedIn }.orEmpty() != owner ||
                session.readAux(owner, "widget_friend:$id").orEmpty() != selection ||
                id !in manager.getAppWidgetIds(ComponentName(context, LocketWidgetProvider::class.java))) continue
            manager.updateAppWidget(id, views)
        }
        success
    }

    private suspend fun buildViews(context: Context, latest: Moment?, scale: Float, width: Int, avatarUrl: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_locket)
        val frame = com.locket.app.ui.screens.AppearancePrefs.frameResource(context)
        views.setViewVisibility(R.id.widget_custom_frame, if (frame == 0) View.GONE else View.VISIBLE)
        if (frame != 0) views.setImageViewResource(R.id.widget_custom_frame, frame)

        // Bam widget -> mo app dung man Home
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_home", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        if (latest == null) {
            // Chua co du lieu that: hien trang thai trong that, khong dung anh mau
            views.setViewVisibility(R.id.widget_image, View.GONE)
            views.setViewVisibility(R.id.widget_footer, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            return views
        }

        views.setViewVisibility(R.id.widget_image, View.VISIBLE)
        views.setViewVisibility(R.id.widget_empty, View.GONE)

        // Same composition at every size: real avatar + caption pill, never a name or time.
        val avatar = loadBitmap(context, avatarUrl, 192)
        val inset = 8f * scale
        val avatarDp = if (avatar != null) 32f * scale else 0f
        val gapDp = if (avatar != null) 6f * scale else 0f
        val caption = WidgetCaptionRenderer.render(context, latest,
            (width - 2 * inset - avatarDp - gapDp).coerceAtLeast(24f), scale)
        val footer = buildFooter(context, avatar, caption, scale)
        views.setViewVisibility(R.id.widget_footer, if (footer == null) View.GONE else View.VISIBLE)
        if (footer != null) {
            views.setImageViewBitmap(R.id.widget_footer, footer)
            val padding = (inset * context.resources.displayMetrics.density).toInt()
            views.setViewPadding(R.id.widget_footer, padding, 0, padding, padding)
            val label = com.locket.app.model.CaptionOverlays.label(latest)
            val review = latest.overlay?.takeIf { it.kind == "review" && it.rating > 0f }
            views.setContentDescription(R.id.widget_footer,
                if (review != null) "${review.rating}/5 sao ${review.comment}".trim() else label)
        }

        val bitmap = loadBitmap(context, latest.thumbnailUrl)
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_image, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_image, R.drawable.image_loading_skeleton)
        }
        return views
    }

    private fun buildFooter(context: Context, avatar: Bitmap?, caption: Bitmap?, scale: Float): Bitmap? {
        if (avatar == null && caption == null) return null
        val density = context.resources.displayMetrics.density
        val avatarSize = if (avatar != null) (32f * scale * density).toInt().coerceAtLeast(1) else 0
        val gap = if (avatar != null && caption != null) (6f * scale * density).toInt() else 0
        val width = avatarSize + gap + (caption?.width ?: 0)
        val height = maxOf(avatarSize, caption?.height ?: 0)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.density = context.resources.displayMetrics.densityDpi
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        if (avatar != null) {
            val side = minOf(avatar.width, avatar.height)
            val shader = android.graphics.BitmapShader(avatar, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            val factor = avatarSize.toFloat() / side
            val top = (height - avatarSize) / 2f
            shader.setLocalMatrix(android.graphics.Matrix().apply {
                setScale(factor, factor)
                postTranslate((side - avatar.width) * factor / 2f, top + (side - avatar.height) * factor / 2f)
            })
            paint.shader = shader
            canvas.drawCircle(avatarSize / 2f, height / 2f, avatarSize / 2f, paint)
            paint.shader = null
        }
        if (caption != null) {
            val left = (avatarSize + gap).toFloat()
            val top = (height - caption.height) / 2f
            canvas.drawBitmap(caption, null, android.graphics.RectF(left, top, left + caption.width, top + caption.height), paint)
        }
        return result
    }

    private suspend fun loadBitmap(context: Context, url: String, size: Int = 900): Bitmap? {
        if (url.isBlank()) return null
        return try {
            // Dung ImageLoader singleton cua app (LocketApplication):
            // disk cache 250 MB da cau hinh san, anh load tu disk khi mo lai.
            val loader = context.imageLoader
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(size, size)
                .build()
            val result = loader.execute(request)
            (result.drawable as? BitmapDrawable)?.bitmap
        } catch (e: Exception) {
            null
        }
    }
}
