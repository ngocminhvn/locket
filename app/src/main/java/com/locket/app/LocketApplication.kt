package com.locket.app

import android.app.Application
import com.locket.app.storage.SessionManager

class LocketApplication : Application() {
    lateinit var sessionManager: SessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessionManager = SessionManager(this)
    }

    companion object {
        lateinit var instance: LocketApplication
            private set
    }
}
