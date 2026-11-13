package com.c2c.app

import android.app.Application
import com.c2c.app.notify.Notifier
import com.c2c.app.work.WorkScheduler

class C2CApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier(this).createChannels()
        WorkScheduler.ensureScheduled(this)
    }
}
