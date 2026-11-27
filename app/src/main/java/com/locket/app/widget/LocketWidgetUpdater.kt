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
import coil.ImageLoader
import coil.request.ImageRequest
import com.locket.app.MainActivity
import com.locket.app.R
import com.locket.app.model.Moment
import com.locket.app.storage.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Cap nhat widget tu cache moments that (SessionManager.getSavedMoments()).
 * Moment moi nhat = phan tu dau tien (fetchMomentsFeed xep getLatestMomentV2 len truoc).
 * Anh thumbnail tai that bang Coil ra Bitmap; that bai thi giu khung hinh, khong dung anh gia.
 */
object LocketWidgetUpdater {

    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val moments = SessionManager(appContext).getSavedMoments()
            val latest = moments.firstOrNull()
            val views = buildViews(appContext, latest)
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, LocketWidgetProvider::class.java)
            )
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }
    }

    private suspend fun buildViews(context: Context, latest: Moment?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_locket)

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
            views.setViewVisibility(R.id.widget_scrim, View.GONE)
            views.setViewVisibility(R.id.widget_info, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            return views
        }

        views.setViewVisibility(R.id.widget_image, View.VISIBLE)
        views.setViewVisibility(R.id.widget_scrim, View.VISIBLE)
        views.setViewVisibility(R.id.widget_info, View.VISIBLE)
        views.setViewVisibility(R.id.widget_empty, View.GONE)

        // Ten that; chua resolve duoc ten thi hien uid (dung quy uoc app goc)
        views.setTextViewText(R.id.widget_name, latest.userName.ifBlank { latest.userUid })
        views.setTextViewText(R.id.widget_time, latest.timeAgo)
        if (latest.caption.isNotBlank()) {
            views.setViewVisibility(R.id.widget_caption, View.VISIBLE)
            views.setTextViewText(R.id.widget_caption, latest.caption)
        } else {
            views.setViewVisibility(R.id.widget_caption, View.GONE)
        }

        val bitmap = loadBitmap(context, latest.thumbnailUrl)
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_image, bitmap)
        }
        return views
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(900, 900)
                .build()
            val result = loader.execute(request)
            (result.drawable as? BitmapDrawable)?.bitmap
        } catch (e: Exception) {
            null
        }
    }
}
