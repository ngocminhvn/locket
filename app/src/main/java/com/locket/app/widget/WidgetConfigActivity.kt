package com.locket.app.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import com.locket.app.storage.SessionManager

class WidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider != ComponentName(this, LocketWidgetProvider::class.java)) {
            finish()
            return
        }
        val session = SessionManager(this)
        val owner = session.currentUser.uid
        if (!session.isLoggedIn || owner.isBlank()) {
            AlertDialog.Builder(this).setMessage("Hãy đăng nhập Locket trước khi thêm widget.")
                .setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        val friends = session.getFriends().filter { it.uid.isNotBlank() && it.uid != owner }.distinctBy { it.uid }
            .sortedBy { it.displayName.ifBlank { it.username } }
        val labels = listOf("Tất cả bạn bè") + friends.map { it.displayName.ifBlank { it.username }.ifBlank { it.uid } }
        AlertDialog.Builder(this).setTitle("Chọn bạn cho widget")
            .setItems(labels.toTypedArray()) { _, which ->
                if (session.isLoggedIn && session.currentUser.uid == owner) {
                    session.writeAux(owner, "widget_friend:$id", friends.getOrNull(which - 1)?.uid.orEmpty())
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    LocketWidgetUpdater.updateAll(this)
                    WidgetRefreshJob.schedule(this, immediate = true)
                }
                finish()
            }.setNegativeButton("Hủy") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
}
