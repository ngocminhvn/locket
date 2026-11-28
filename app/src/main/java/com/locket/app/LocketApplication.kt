package com.locket.app

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.locket.app.storage.SessionManager
import com.locket.app.storage.db.LocketCache
import com.locket.app.storage.db.LocketDatabase

class LocketApplication : Application(), ImageLoaderFactory {
    lateinit var sessionManager: SessionManager
        private set

    internal lateinit var ownAvatarStore: com.locket.app.storage.OwnAvatarStore
        private set

    lateinit var database: LocketDatabase
        private set

    // Cache that Room — nguon hien thi chinh cua app (xem LocketCache).
    lateinit var cache: LocketCache
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createMessageChannel()
        sessionManager = SessionManager(this)
        ownAvatarStore = com.locket.app.storage.OwnAvatarStore(this, sessionManager)
        database = Room.databaseBuilder(this, LocketDatabase::class.java, "locket_cache.db")
            // DB moi lap lan dau (version 1); xoa + tao lai neu schema doi
            // o ban phat trien sau — cache chi la ban sao du lieu API nen
            // xoa khong mat du lieu that (sync lai la co).
            .addMigrations(object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN lastMessageAtMs INTEGER")
                }
            })
            .addMigrations(object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE moments ADD COLUMN overlaysJson TEXT NOT NULL DEFAULT '[]'")
                }
            })
            .fallbackToDestructiveMigration()
            .build()
        cache = LocketCache(database, sessionManager)
    }

    /**
     * Coil dung chung 1 ImageLoader cho ca app (ke ca widget) voi disk
     * cache ro rang 250 MB. Header behavior follows Coil 2 defaults;
     * cache-control and token rotation have not been measured on device.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    private fun createMessageChannel() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(
            MESSAGE_CHANNEL, "Tin nhắn", android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Thông báo tin nhắn Locket mới"
            enableVibration(true)
        })
    }

    fun notifyNewMessage(conversation: com.locket.app.model.Conversation) {
        if (!sessionManager.isLoggedIn) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val launch = android.content.Intent(this, MainActivity::class.java)
            .putExtra("open_chat", true)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = android.app.PendingIntent.getActivity(this, 0, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val name = conversation.friend.displayName.takeIf { it.isNotBlank() && it != conversation.friend.uid }
        val notification = androidx.core.app.NotificationCompat.Builder(this, MESSAGE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(name ?: "Locket")
            .setContentText(conversation.lastMessage.ifBlank { "Tin nhắn mới" })
            .setContentIntent(pending)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify("chat:" + conversation.id, 1, notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked since the check.
        }
    }

    fun clearMessageNotifications() {
        getSystemService(android.app.NotificationManager::class.java).cancelAll()
    }

    companion object {
        private const val MESSAGE_CHANNEL = "chat_messages"

        lateinit var instance: LocketApplication
            private set
    }
}
