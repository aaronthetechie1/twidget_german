package com.tjg.twidget.brief

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.tjg.twidget.R
import com.tjg.twidget.main.AccountGoalSettings
import com.tjg.twidget.main.MilestoneCopyFactory
import com.tjg.twidget.main.MilestoneMetricSnapshot
import com.tjg.twidget.main.MilestonePerformanceState
import com.tjg.twidget.main.MilestonePolicy

/** Goal labels and measured progress remain independent of generated editorial descriptions. */
internal object BriefGoalCardCopy {
    fun bind(context: Context, root: View, account: String, settings: AccountGoalSettings?, metric: MilestoneMetricSnapshot?) {
        val strings = BriefStrings.from(context)
        val title: String
        val detail: String
        if (settings == null) {
            title = context.getString(R.string.milestone_account_goals)
            detail = context.getString(R.string.milestone_setup_hint)
        } else {
            val current = metric?.value
            val progress = MilestonePolicy.progress(current, settings.target)
            val noun = strings.text(settings.metric.goalNounRes)
            title = if (progress == null) strings.text(R.string.brief_goal_heading, noun)
                else MilestoneCopyFactory.message(context, "$account:${settings.metric.storageId}",
                    metric?.let { MilestonePolicy.performanceState(it.history) } ?: MilestonePerformanceState.NEUTRAL,
                    progress, BriefGoalCopy.value(settings.metric, settings.target, strings), noun).title
            detail = BriefGoalCopy.cardDetail(settings.metric, current, settings.target, strings)
        }
        root.findViewById<TextView>(R.id.milestone_title).apply {
            text = title
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 18, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        root.findViewById<TextView>(R.id.milestone_message).apply {
            text = detail
            visibility = View.VISIBLE
        }
        root.contentDescription = "$title. $detail"
    }
}
