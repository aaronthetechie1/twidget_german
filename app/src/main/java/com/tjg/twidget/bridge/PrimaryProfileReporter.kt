package com.tjg.twidget.bridge

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.data.TwidgetStore
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Opt-in install-scoped default selection, not a claim of X account ownership. */
object PrimaryProfileReporter {
    private const val PREFS = "twidget_primary_profile"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun enqueue(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = TwidgetStore.settings(app)
        if (!settings.shareHistory && prefs.getString("endpoint", null) == null) return
        val stats = TwidgetStore.currentStats(app, settings.username)
        val queuedFor = "${TwidgetStore.bridgeEndpoint(settings).url}:${primaryProfileUsername(settings.shareHistory, settings.username, stats.userName, stats.isPrivate)}"
        // Network retries are durable. Repeated profile hydration need not grow the work queue.
        synchronized(this) {
            if (!force && prefs.getString("queued_for", null) == queuedFor &&
                prefs.getLong("queued_at", 0L) > System.currentTimeMillis() - 60 * 60 * 1000L) return
            prefs.edit().putLong("queued_at", System.currentTimeMillis()).putString("queued_for", queuedFor).apply()
        }
        AppExecutors.execute(onRejected = { prefs.edit().remove("queued_at").apply() }) {
            WorkManager.getInstance(app).enqueueUniqueWork(
                "twidget-primary-profile", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PrimaryProfileSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
            )
        }
    }

    /** Called only by the serial WorkManager chain, off the main thread. */
    internal fun sync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = TwidgetStore.settings(context)
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        val profile = TwidgetStore.currentStats(context, settings.username)
        val username = primaryProfileUsername(settings.shareHistory, settings.username, profile.userName, profile.isPrivate)
        val previousEndpoint = prefs.getString("endpoint", null)
        var key = prefs.getString("install_key", null)

        fun send(url: String, token: String, name: String?): Boolean {
            val headers = buildMap {
                put("Content-Type", "application/json")
                put("X-Twidget-Install-Key", requireNotNull(key))
                if (token.isNotBlank()) put("Authorization", "Bearer $token")
            }
            // Never put the install capability or its request body into BridgeLog.
            val result = HttpTransport.post("${url.trimEnd('/')}/installation/primary-profile",
                JSONObject().put("username", name ?: JSONObject.NULL).toString(), headers)
            return result.code == 204
        }

        if (previousEndpoint != null && (username == null || previousEndpoint != endpoint.url || prefs.getString("username", null) != username)) {
            // A timed-out clear can also have succeeded; do not later suppress a needed re-report.
            check(prefs.edit().remove("username").remove("synced_at").commit())
            val cleanupToken = if (previousEndpoint == endpoint.url) endpoint.token else
                SecureCredentialStore.read(context, SecureCredentialStore.PRIMARY_PROFILE_BRIDGE_TOKEN)
            if (!send(previousEndpoint, cleanupToken, null)) return false
            check(prefs.edit().remove("endpoint").remove("username").remove("synced_at").commit())
            SecureCredentialStore.clear(context, SecureCredentialStore.PRIMARY_PROFILE_BRIDGE_TOKEN)
        }
        if (username == null) return true
        if (previousEndpoint == endpoint.url && prefs.getString("username", null) == username &&
            prefs.getLong("synced_at", 0L) > System.currentTimeMillis() - DAY_MS) return true
        if (key == null) {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            key = bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            check(prefs.edit().putString("install_key", key).commit())
        }
        // Persist cleanup information before sending: a timed-out write might have succeeded.
        SecureCredentialStore.write(context, mapOf(SecureCredentialStore.PRIMARY_PROFILE_BRIDGE_TOKEN to endpoint.token))
        check(prefs.edit().putString("endpoint", endpoint.url).remove("username").remove("synced_at").commit())
        if (!send(endpoint.url, endpoint.token, username)) return false
        check(prefs.edit().putString("username", username).putLong("synced_at", System.currentTimeMillis()).commit())
        // If a setting changed during the HTTP call, reconcile it in the durable retry.
        val latest = TwidgetStore.settings(context)
        return latest.shareHistory == settings.shareHistory && latest.username.equals(settings.username, true) &&
            TwidgetStore.bridgeEndpoint(latest) == endpoint
    }
}

internal fun primaryProfileUsername(shareHistory: Boolean, defaultUsername: String, observedUsername: String, isPrivate: Boolean?): String? {
    val clean = defaultUsername.trim().trimStart('@')
    return clean.lowercase(Locale.US).takeIf {
        shareHistory && isPrivate == false && Regex("[A-Za-z0-9_]{1,15}").matches(clean) &&
            clean.equals(observedUsername.trim().trimStart('@'), true)
    }
}

class PrimaryProfileSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        if (PrimaryProfileReporter.sync(applicationContext)) Result.success() else Result.retry()
    } catch (_: Exception) { Result.retry() }
}
