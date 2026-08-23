package com.nutrilens.app

import android.app.Application
import com.nutrilens.app.bg.ReminderSync
import com.nutrilens.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NutriLensApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        appScope.launch {
            ReminderSync.sync(this@NutriLensApplication)
        }
    }
}