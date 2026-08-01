package com.tjg.twidget.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
import androidx.picker.widget.SeslNumberPicker
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.R as OneUiIconR
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow

class MilestoneGoalActivity : EdgeToEdgeActivity() {
    private lateinit var account: String
    private lateinit var metricStep: View
    private lateinit var targetStep: View
    private lateinit var continueButton: TextView
    private lateinit var picker: SeslNumberPicker
    private lateinit var autoSwitch: SwitchCompat
    private var selectedMetric = MilestoneMetric.FOLLOWERS
    private var targetOptions = emptyList<Double>()
    private var showingTarget = false
    private val metricRows = linkedMapOf<MilestoneMetric, RadioButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = intent.getStringExtra(EXTRA_ACCOUNT)
            ?.trim()?.trimStart('@')
            .orEmpty()
            .ifBlank { TwidgetStore.settings(this).username }
        if (account.isBlank()) {
            finish()
            return
        }
        setContentView(R.layout.activity_milestone_goal)
        applyEdgeToEdgeInsets(findViewById(R.id.milestone_setup_root))
        metricStep = findViewById(R.id.milestone_metric_step)
        targetStep = findViewById(R.id.milestone_target_step)
        continueButton = findViewById(R.id.milestone_continue)
        picker = findViewById(R.id.milestone_number_picker)
        autoSwitch = findViewById(R.id.milestone_auto_switch)

        val saved = MilestoneGoalStore.read(this, account)
        selectedMetric = saved.metric
        autoSwitch.isChecked = saved.autoAdjust
        buildMetricRows()
        findViewById<View>(R.id.milestone_auto_row).setOnClickListener {
            autoSwitch.isChecked = !autoSwitch.isChecked
        }
        findViewById<View>(R.id.milestone_cancel).setOnClickListener { finish() }
        continueButton.setOnClickListener {
            if (showingTarget) saveGoal() else showTargetStep()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingTarget) showMetricStep() else finish()
            }
        })
    }

    private fun buildMetricRows() {
        val host = findViewById<LinearLayout>(R.id.milestone_metric_rows)
        host.removeAllViews()
        val snapshots = MilestoneMetric.entries.associateWith(::snapshot)
        MilestoneMetric.entries.forEach { metric ->
            val available = snapshots.getValue(metric).available
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), dp(12), dp(12), dp(12))
                minimumHeight = dp(96)
                isEnabled = available
                alpha = if (available) 1f else 0.48f
                isClickable = available
                isFocusable = available
            }
            row.addView(ImageView(this).apply {
                setImageResource(iconFor(metric))
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(24), dp(24)))

            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, dp(8), 0)
            }
            copy.addView(TextView(this).apply {
                text = getString(metricLabel(metric))
                textSize = 18f
                setTextColor(getColor(R.color.oneui_text_primary))
                typeface = Typeface.create("sec", Typeface.NORMAL)
            })
            copy.addView(TextView(this).apply {
                text = if (available) {
                    getString(metricSummary(metric))
                } else if (metric == MilestoneMetric.VERIFIED_FOLLOWERS) {
                    getString(R.string.milestone_unavailable_verified)
                } else {
                    getString(R.string.milestone_unavailable_analytics)
                }
                textSize = 14f
                setTextColor(getColor(R.color.oneui_text_secondary))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val radio = RadioButton(this).apply {
                isChecked = metric == selectedMetric && available
                isEnabled = available
                contentDescription = getString(metricLabel(metric))
            }
            metricRows[metric] = radio
            row.addView(radio, LinearLayout.LayoutParams(dp(48), dp(48)))
            row.setOnClickListener { selectMetric(metric) }
            radio.setOnClickListener { selectMetric(metric) }
            host.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        if (snapshots[selectedMetric]?.available != true) {
            MilestoneMetric.entries.firstOrNull { snapshots.getValue(it).available }?.let(::selectMetric)
        }
        continueButton.isEnabled = snapshots.values.any { it.available }
        continueButton.alpha = if (continueButton.isEnabled) 1f else 0.48f
    }

    private fun selectMetric(metric: MilestoneMetric) {
        if (snapshot(metric).available.not()) return
        selectedMetric = metric
        metricRows.forEach { (candidate, radio) -> radio.isChecked = candidate == metric }
    }

    private fun showTargetStep() {
        val current = snapshot(selectedMetric).value
        if (current == null) {
            Toast.makeText(this, R.string.milestone_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val saved = MilestoneGoalStore.read(this, account)
        targetOptions = goalOptions(selectedMetric, current, saved.target.takeIf {
            saved.configured && saved.metric == selectedMetric
        })
        picker.minValue = 0
        picker.maxValue = targetOptions.lastIndex
        picker.displayedValues = null
        picker.displayedValues = targetOptions.map { formatMetricValue(selectedMetric, it) }.toTypedArray()
        picker.value = targetOptions.indexOfFirst { it == saved.target }.takeIf { it >= 0 }
            ?: targetOptions.indexOfFirst { it > current }.coerceAtLeast(0)
        findViewById<TextView>(R.id.milestone_target_title).text =
            getString(R.string.milestone_enter_goal, metricGoalNoun(selectedMetric))
        autoSwitch.isChecked = if (saved.configured) saved.autoAdjust else true
        showingTarget = true
        metricStep.visibility = View.GONE
        targetStep.visibility = View.VISIBLE
        continueButton.setText(R.string.milestone_save_goal)
    }

    private fun showMetricStep() {
        showingTarget = false
        targetStep.visibility = View.GONE
        metricStep.visibility = View.VISIBLE
        continueButton.setText(R.string.milestone_continue)
    }

    private fun saveGoal() {
        val current = snapshot(selectedMetric).value ?: return
        val target = targetOptions.getOrNull(picker.value) ?: return
        if (target <= current) {
            Toast.makeText(this, R.string.milestone_target_must_exceed, Toast.LENGTH_SHORT).show()
            return
        }
        MilestoneGoalStore.save(
            this,
            account,
            AccountGoalSettings(
                configured = true,
                metric = selectedMetric,
                target = target,
                autoAdjust = autoSwitch.isChecked,
            ),
        )
        TwidgetWidget.updateAll(this)
        setResult(RESULT_OK)
        finish()
    }

    private fun snapshot(metric: MilestoneMetric): MilestoneMetricSnapshot =
        MilestoneMetricResolver.resolve(
            context = this,
            account = account,
            metric = metric,
            stats = TwidgetStore.currentStats(this, account),
            history = TwidgetStore.rangedHistory(this, account, HistoryRange.MONTH),
            analytics = AnalyticsClient.cached(this, account),
            imported = ImportedAnalyticsStore.recent(this, account, 30),
        )

    private fun goalOptions(metric: MilestoneMetric, current: Double, saved: Double?): List<Double> {
        if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            return (1..100).map { it / 100.0 }
        }
        val safe = current.coerceAtLeast(0.0)
        val magnitude = 10.0.pow((kotlin.math.log10(safe.coerceAtLeast(10.0))).toInt())
        val step = when {
            safe < 100 -> 10.0
            safe < 1_000 -> 100.0
            else -> magnitude
        }
        val first = (ceil(safe / step) * step - step * 4).coerceAtLeast(step)
        return ((0..30).map { first + step * it } + listOfNotNull(saved))
            .filter { it > 0.0 }
            .distinct()
            .sorted()
    }

    private fun formatMetricValue(metric: MilestoneMetric, value: Double): String =
        if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            "${(value * 100).toInt()}%"
        } else {
            NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())
        }

    private fun metricLabel(metric: MilestoneMetric): Int = when (metric) {
        MilestoneMetric.FOLLOWERS -> R.string.milestone_metric_followers
        MilestoneMetric.VERIFIED_FOLLOWERS -> R.string.milestone_metric_verified
        MilestoneMetric.ENGAGEMENT_RATE -> R.string.milestone_metric_engagement
        MilestoneMetric.IMPRESSIONS -> R.string.milestone_metric_impressions
    }

    private fun metricSummary(metric: MilestoneMetric): Int = when (metric) {
        MilestoneMetric.FOLLOWERS -> R.string.milestone_metric_followers_summary
        MilestoneMetric.VERIFIED_FOLLOWERS -> R.string.milestone_metric_verified_summary
        MilestoneMetric.ENGAGEMENT_RATE -> R.string.milestone_metric_engagement_summary
        MilestoneMetric.IMPRESSIONS -> R.string.milestone_metric_impressions_summary
    }

    private fun metricGoalNoun(metric: MilestoneMetric): String = when (metric) {
        MilestoneMetric.FOLLOWERS -> getString(R.string.milestone_metric_followers).lowercase()
        MilestoneMetric.VERIFIED_FOLLOWERS -> getString(R.string.milestone_metric_verified).lowercase()
        MilestoneMetric.ENGAGEMENT_RATE -> getString(R.string.milestone_metric_engagement).lowercase()
        MilestoneMetric.IMPRESSIONS -> getString(R.string.milestone_metric_impressions).lowercase()
    }

    private fun iconFor(metric: MilestoneMetric): Int = when (metric) {
        MilestoneMetric.FOLLOWERS -> OneUiIconR.drawable.ic_oui_community
        MilestoneMetric.VERIFIED_FOLLOWERS -> OneUiIconR.drawable.ic_oui_checkbox_checked_outline
        MilestoneMetric.ENGAGEMENT_RATE -> OneUiIconR.drawable.ic_oui_equalizer_2
        MilestoneMetric.IMPRESSIONS -> OneUiIconR.drawable.ic_oui_eyes
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ACCOUNT = "account"

        fun intent(context: Context, account: String): Intent =
            Intent(context, MilestoneGoalActivity::class.java)
                .putExtra(EXTRA_ACCOUNT, account)
    }
}
