package com.tjg.twidget.main

import android.content.Context
import com.tjg.twidget.data.TwidgetStore
import java.util.Locale

internal object MilestoneGoalStore {
    private const val PREFS = "twidget_account_goals"

    fun read(context: Context, username: String): AccountGoalSettings {
        val key = accountKey(username)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("${key}_configured", false)) {
            // Preserve the follower target created by PR #12's earlier dialog.
            val legacy = TwidgetStore.milestoneSettings(context, username)
            val legacyTarget = legacy.target
            if (legacyTarget != null) {
                return AccountGoalSettings(
                    configured = true,
                    metric = MilestoneMetric.FOLLOWERS,
                    target = legacyTarget.toDouble(),
                    autoAdjust = false,
                )
            }
            return AccountGoalSettings()
        }
        return AccountGoalSettings(
            configured = true,
            metric = MilestoneMetric.fromStorageId(prefs.getString("${key}_metric", null)),
            target = prefs.getString("${key}_target", null)?.toDoubleOrNull() ?: 0.0,
            autoAdjust = prefs.getBoolean("${key}_auto", true),
        )
    }

    fun save(context: Context, username: String, settings: AccountGoalSettings) {
        val key = accountKey(username)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("${key}_configured", settings.configured)
            .putString("${key}_metric", settings.metric.storageId)
            .putString("${key}_target", settings.target.toString())
            .putBoolean("${key}_auto", settings.autoAdjust)
            .apply()

        // Keep the old setting in sync so existing milestone consumers and the
        // new widget implementation receive the follower goal immediately.
        if (settings.configured && settings.metric == MilestoneMetric.FOLLOWERS) {
            TwidgetStore.saveMilestoneSettings(
                context,
                username,
                MilestoneSettings(
                    target = settings.target.toLong().takeIf { it > 0L },
                    labelRaw = settings.target.toLong().toString(),
                    showPercent = true,
                ),
            )
        }
    }

    private fun accountKey(username: String): String =
        username.trim().trimStart('@').lowercase(Locale.US).ifBlank { "default" }
}
