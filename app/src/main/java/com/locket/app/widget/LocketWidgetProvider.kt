package com.locket.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * Home Screen Widget: hien moment moi nhat (thumbnail that + ten nguoi gui + thoi gian).
 * Du lieu that tu cache SessionManager (moments da sync tu server), khong dung anh mau/mock.
 * Bam vao widget mo app dung man Home (extra "open_home").
 * Widget duoc cap nhat moi khi LocketViewModel.syncAllData luu moments moi.
 */
class LocketWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        LocketWidgetUpdater.updateAll(context)
    }
}
