package com.tjg.twidget.main

import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.picker.widget.SeslNumberPicker
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.widget.TwidgetWidget
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow

internal object MilestoneGoalDialog {
    fun show(
        activity: AppCompatActivity,
        username: String,
        metric: MilestoneMetric,
        onSaved: () -> Unit,
    ) {
        val account = username.trim().trimStart('@')
        if (account.isBlank()) return

        val existing = MilestoneGoalStore.readAll(activity, account)
            .firstOrNull { it.metric == metric }
        val current = currentValue(activity, account, metric)
        val options = goalOptions(metric, current, existing?.target)
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_milestone_goal, null)
        val picker = content.findViewById<SeslNumberPicker>(R.id.milestone_number_picker).apply {
            displayedValues = null
            minValue = 0
            maxValue = options.lastIndex
            displayedValues = options.map { formatValue(metric, it) }.toTypedArray()
            value = options.indexOfFirst { it == existing?.target }.takeIf { it >= 0 }
                ?: options.indexOfFirst { it > current }.takeIf { it >= 0 }
                ?: options.lastIndex
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.milestone_select_goal)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.milestone_select, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target = options.getOrNull(picker.value) ?: return@setOnClickListener
                if (target <= current) {
                    Toast.makeText(activity, R.string.milestone_target_must_exceed, Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                val updated = MilestoneGoalStore.readAll(activity, account)
                    .filterNot { it.metric == metric } + AccountGoalSettings(
                    configured = true,
                    metric = metric,
                    target = target,
                    autoAdjust = MilestoneGoalStore.autoAdjust(activity, account),
                )
                MilestoneGoalStore.saveAll(activity, account, updated)
                TwidgetWidget.updateAll(activity)
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun currentValue(
        activity: AppCompatActivity,
        account: String,
        metric: MilestoneMetric,
    ): Double = MilestoneMetricResolver.resolve(
        context = activity,
        account = account,
        metric = metric,
        stats = TwidgetStore.currentStats(activity, account),
        history = TwidgetStore.fullHistory(activity, account),
        analytics = AnalyticsClient.cached(activity, account),
        imported = ImportedAnalyticsStore.all(activity, account),
    ).value ?: 0.0

    internal fun goalOptions(
        metric: MilestoneMetric,
        current: Double,
        saved: Double?,
    ): List<Double> {
        if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            return ((1..100).map { it / 100.0 } + listOfNotNull(saved))
                .distinct()
                .sorted()
        }
        val safe = current.coerceAtLeast(0.0)
        val step = when {
            metric == MilestoneMetric.IMPRESSIONS && safe <= 0.0 -> 1_000.0
            safe < 100 -> 10.0
            safe < 1_000 -> 100.0
            else -> 10.0.pow(kotlin.math.log10(safe.coerceAtLeast(10.0)).toInt())
        }
        val first = (ceil(safe / step) * step - step * 4).coerceAtLeast(step)
        return ((0..30).map { first + step * it } + listOfNotNull(saved))
            .filter { it > 0.0 }
            .distinct()
            .sorted()
    }

    internal fun formatValue(metric: MilestoneMetric, value: Double): String =
        if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            "${(value * 100).toInt()}%"
        } else {
            NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())
        }
}
