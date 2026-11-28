package com.locket.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.locket.app.storage.SessionManager

class LocketWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Schedule first so the OS can complete the refresh even if it stops the receiver process.
        WidgetRefreshJob.schedule(context, immediate = true)
        LocketWidgetUpdater.updateAll(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val session = SessionManager(context)
        val owner = session.currentUser.uid
        appWidgetIds.forEach { session.writeAux(owner, "widget_friend:$it", null) }
    }

    override fun onDisabled(context: Context) {
        WidgetRefreshJob.cancel(context)
    }
}
