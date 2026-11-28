package com.locket.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.locket.app.storage.SessionManager

class LocketWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        LocketWidgetUpdater.updateAll(context).invokeOnCompletion { pending.finish() }
        WidgetRefreshJob.schedule(context, immediate = true)
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
