package com.tjg.twidget.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SwitchCompat
import com.tjg.twidget.R
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.R as OneUiIconR

class MilestoneGoalActivity : EdgeToEdgeActivity() {
    private lateinit var account: String
    private lateinit var rows: LinearLayout
    private lateinit var autoSwitch: SwitchCompat
    private var bindingAutoSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = intent.getStringExtra(EXTRA_ACCOUNT)
            ?.trim()?.trimStart('@')
            .orEmpty()
            .ifBlank { com.tjg.twidget.data.TwidgetStore.settings(this).username }
        if (account.isBlank()) {
            finish()
            return
        }

        setContentView(R.layout.activity_milestone_goal)
        applyEdgeToEdgeInsets(findViewById(R.id.milestone_setup_root))
        rows = findViewById(R.id.milestone_metric_rows)
        autoSwitch = findViewById(R.id.milestone_auto_switch)

        findViewById<ImageButton>(R.id.milestone_back).apply {
            setImageDrawable(AppCompatResources.getDrawable(
                this@MilestoneGoalActivity,
                OneUiIconR.drawable.ic_oui_back,
            ))
            imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
            setOnClickListener { finish() }
        }
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingAutoSwitch) return@setOnCheckedChangeListener
            MilestoneGoalStore.setAutoAdjust(this, account, checked)
            TwidgetWidget.updateAll(this)
            setResult(RESULT_OK)
        }
        findViewById<View>(R.id.milestone_auto_row).setOnClickListener {
            autoSwitch.performClick()
        }
        refresh()
    }

    private fun refresh() {
        val goals = MilestoneGoalStore.readAll(this, account).associateBy(AccountGoalSettings::metric)
        bindingAutoSwitch = true
        autoSwitch.isChecked = MilestoneGoalStore.autoAdjust(this, account)
        bindingAutoSwitch = false

        rows.removeAllViews()
        MilestoneMetric.entries.forEachIndexed { index, metric ->
            rows.addView(goalRow(metric, goals[metric]))
            if (index != MilestoneMetric.entries.lastIndex) rows.addView(separator())
        }
    }

    private fun goalRow(metric: MilestoneMetric, goal: AccountGoalSettings?): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            minimumHeight = dp(85)
            isClickable = true
            isFocusable = true
            background = selectableItemBackground()

            addView(ImageView(context).apply {
                setImageResource(iconFor(metric))
                imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(24), dp(24)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, dp(8), 0)
                addView(TextView(context).apply {
                    text = getString(metricLabel(metric))
                    textSize = 18f
                    includeFontPadding = false
                    setTextColor(getColor(R.color.oneui_text_primary))
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                })
                addView(TextView(context).apply {
                    text = goal?.let {
                        getString(
                            R.string.milestone_current_goal,
                            MilestoneGoalDialog.formatValue(metric, it.target),
                        )
                    } ?: getString(metricSummary(metric))
                    textSize = 14f
                    includeFontPadding = false
                    setTextColor(getColor(R.color.oneui_text_secondary))
                    setLineSpacing(dp(2).toFloat(), 1f)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(ImageView(context).apply {
                setImageResource(OneUiIconR.drawable.ic_oui_edit_outline)
                imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(24), dp(24)))

            contentDescription = buildString {
                append(getString(metricLabel(metric)))
                append(". ")
                append(goal?.let {
                    getString(
                        R.string.milestone_current_goal,
                        MilestoneGoalDialog.formatValue(metric, it.target),
                    )
                } ?: getString(metricSummary(metric)))
            }
            setOnClickListener {
                MilestoneGoalDialog.show(this@MilestoneGoalActivity, account, metric) {
                    setResult(RESULT_OK)
                    refresh()
                }
            }
        }

    private fun separator(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.oneui_divider))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun selectableItemBackground() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        AppCompatResources.getDrawable(this, value.resourceId)
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
