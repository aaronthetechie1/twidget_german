package com.tjg.twidget.followers

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager

/** Run off the main thread; never opt users into shared history during migration. */
object TopFollowersLocalScanCleanup {
    @Synchronized
    fun run(context: Context) {
        val prefs = context.getSharedPreferences("twidget_top_followers_migrations", Context.MODE_PRIVATE)
        if (prefs.getBoolean("bridge_only", false)) return
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(TopFollowersScanWorker::class.java.name).result.get()
        TopFollowersStore.clearLocalScanState(context)
        context.getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel("top_followers_scan_progress")
        prefs.edit().putBoolean("bridge_only", true).apply()
    }
}
