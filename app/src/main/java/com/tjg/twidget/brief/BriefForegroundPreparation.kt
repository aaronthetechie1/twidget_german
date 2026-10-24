package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetAppVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Foreground-only preparation: no alarms, workers, model downloads or draft analysis. */
internal object BriefForegroundPreparation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null
    private var stop: Job? = null
    private val policy = BriefPreparationPolicy()

    fun install(context: Context) {
        val app = context.applicationContext
        TwidgetAppVisibility.addVisibilityListener { visible ->
            stop?.cancel()
            if (visible) {
                if (session?.isActive == true) return@addVisibilityListener
                session = scope.launch(Dispatchers.IO) {
                    // Let launch rendering and the first analytics refresh finish.
                    delay(3_000)
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        if (TwidgetAppVisibility.isVisible() &&
                            BriefSettingsStore.enabled(app) && BriefSettingsStore.onboardingComplete(app)) {
                            val account = TwidgetStore.settings(app).username.trim().trimStart('@')
                            val key = listOf(account.lowercase(java.util.Locale.US), BriefSettingsStore.provider(app),
                                BriefAiCoordinator.nanoModelMode(app), BriefSettingsStore.contentFingerprint(app),
                                BriefStrings.from(app).languageTag, BriefSettingsStore.cloudApiKey(app).isNotBlank()).joinToString("|")
                            if (account.isNotBlank() && policy.canAttempt(key, now)) {
                                try {
                                    val source = BriefEngine.rebuild(app, account)
                                    if (!source.hasGeneratedNarrative || !BriefRunCache.providerMatches(
                                            BriefSettingsStore.provider(app), source.providerUsed)) {
                                        // Failed/unsupported providers do not spin every minute or every reopen.
                                        policy.attempted(key, now)
                                        val result = withTimeoutOrNull(180_000L) { BriefAiCoordinator.enrich(app, source) }
                                        if (result?.snapshot?.hasGeneratedNarrative == true) policy.succeeded()
                                    }
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    policy.attempted(key, now)
                                }
                            }
                        }
                        delay(60_000L)
                    }
                }
            } else {
                // Activity transitions briefly reach zero visible windows. Keep
                // the shared request alive when the user opens the Brief itself.
                stop = scope.launch {
                    delay(700)
                    if (!TwidgetAppVisibility.isVisible()) {
                        session?.cancel()
                        session = null
                    }
                }
            }
        }
    }
}

internal class BriefPreparationPolicy {
    private var lastKey = ""
    private var retryAt = 0L
    fun canAttempt(key: String, now: Long): Boolean = key != lastKey || now >= retryAt
    fun attempted(key: String, now: Long) { lastKey = key; retryAt = now + 15 * 60_000L }
    fun succeeded() { retryAt = 0L }
}
