package com.tjg.twidget.bridge

import android.content.Context
import androidx.work.*
import com.tjg.twidget.data.TwidgetStore
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Local migration remains usable offline; opt-in bridge moves retry durably. */
class BridgeHandleMigrationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        if (!TwidgetStore.settings(applicationContext).shareHistory) return Result.success()
        val pending = synchronized(TwidgetStore) {
            TwidgetStore.recoverAccountMigration(applicationContext)
            prefs.all.filterKeys { it.startsWith("bridge_handle_migration_") }
        }
        var failed = false
        pending.forEach { (key, value) ->
            try {
                val record = JSONObject(value as String)
                val next = TwidgetStore.canonicalAccount(applicationContext, record.getString("next"))
                HistoryPool.migrateHandle(applicationContext, record.getString("old"), next, record.getString("userId"))
                synchronized(TwidgetStore) {
                    if (prefs.getString(key, null) == value) check(prefs.edit().remove(key).commit())
                }
            } catch (_: Exception) { failed = true }
        }
        return if (failed) Result.retry() else Result.success()
    }
    companion object {
        fun enqueueIfPending(context: Context) {
            val pending = synchronized(TwidgetStore) {
                context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).all.keys.any { it.startsWith("bridge_handle_migration_") }
            }
            if (!pending) return
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "twidget-handle-migrations", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BridgeHandleMigrationWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
            )
        }
    }
}
