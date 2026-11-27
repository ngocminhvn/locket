package com.locket.app

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.locket.app.storage.SessionManager
import com.locket.app.storage.db.LocketCache
import com.locket.app.storage.db.LocketDatabase

class LocketApplication : Application(), ImageLoaderFactory {
    lateinit var sessionManager: SessionManager
        private set

    lateinit var database: LocketDatabase
        private set

    // Cache that Room — nguon hien thi chinh cua app (xem LocketCache).
    lateinit var cache: LocketCache
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessionManager = SessionManager(this)
        database = Room.databaseBuilder(this, LocketDatabase::class.java, "locket_cache.db")
            // DB moi lap lan dau (version 1); xoa + tao lai neu schema doi
            // o ban phat trien sau — cache chi la ban sao du lieu API nen
            // xoa khong mat du lieu that (sync lai la co).
            .fallbackToDestructiveMigration()
            .build()
        cache = LocketCache(database, sessionManager)
    }

    /**
     * Coil dung chung 1 ImageLoader cho ca app (ke ca widget) voi disk
     * cache ro rang 250 MB, ton trong Cache-Control cua server.
     * (Coil 2 khong co API respectCacheHeaders tren DiskCache.Builder —
     * chi co o Coil 3 — nen khong goi o day.)
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

    companion object {
        lateinit var instance: LocketApplication
            private set
    }
}
