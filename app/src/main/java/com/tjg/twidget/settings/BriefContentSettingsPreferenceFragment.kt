package com.tjg.twidget.settings

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.PreferenceCategory
import androidx.preference.SeslSwitchPreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefContentCategory
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.brief.BriefVisuals
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MilestoneGoalActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startRightSidePopOverActivity
import com.tjg.twidget.widget.TwidgetBriefWidget

class BriefContentSettingsPreferenceFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(categorySwitch(BriefContentCategory.TOP_TWEET, R.string.brief_content_top_tweet))
        screen.addPreference(categorySwitch(BriefContentCategory.WORST_TWEET, R.string.brief_content_worst_tweet))

        screen.addPreference(spacerCategory())
        screen.addPreference(categorySwitch(BriefContentCategory.FOLLOWERS, R.string.brief_content_followers))
        screen.addPreference(categorySwitch(BriefContentCategory.TOP_FOLLOWERS, R.string.brief_content_top_followers))

        screen.addPreference(spacerCategory())
        screen.addPreference(navigableCategorySwitch(
            category = BriefContentCategory.TWEET_ACTIVITY,
            titleRes = R.string.brief_content_tweet_activity,
        ) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.brief_content_tweet_activity)
                .setMessage(R.string.brief_tweet_activity_explainer)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        })
        screen.addPreference(categorySwitch(
            BriefContentCategory.SCHEDULED_TWEETS,
            R.string.brief_content_scheduled_tweets,
        ))

        screen.addPreference(spacerCategory())
        screen.addPreference(navigableCategorySwitch(
            category = BriefContentCategory.ACCOUNT_GOALS,
            titleRes = R.string.brief_content_account_goals,
        ) {
            val account = TwidgetStore.settings(requireContext()).username
            if (account.isNotBlank()) {
                requireActivity().startRightSidePopOverActivity(
                    MilestoneGoalActivity.intent(requireContext(), account),
                )
            }
        })

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun categorySwitch(
        category: BriefContentCategory,
        titleRes: Int,
    ) = SwitchPreferenceCompat(requireContext()).apply {
        configureCategory(this, category, titleRes)
    }

    private fun navigableCategorySwitch(
        category: BriefContentCategory,
        titleRes: Int,
        onOpen: () -> Unit,
    ) = SeslSwitchPreferenceScreen(requireContext()).apply {
        configureCategory(this, category, titleRes)
        setOnPreferenceClickListener {
            onOpen()
            true
        }
    }

    private fun configureCategory(
        preference: SwitchPreferenceCompat,
        category: BriefContentCategory,
        titleRes: Int,
    ) {
        val context = requireContext()
        preference.key = "brief_content_${category.storageId}"
        preference.title = getString(titleRes)
        preference.isPersistent = false
        preference.isChecked = BriefSettingsStore.contentEnabled(context, category)
        preference.icon = tintedIcon(category)
        preference.isIconSpaceReserved = true
        preference.setOnPreferenceChangeListener { _, newValue ->
            BriefSettingsStore.setContentEnabled(context, category, newValue as Boolean)
            refreshBrief()
            true
        }
    }

    private fun tintedIcon(category: BriefContentCategory): Drawable? {
        val context = requireContext()
        val tinted = AppCompatResources.getDrawable(context, BriefVisuals.categoryIcon(category))
            ?.mutate()
            ?.also { DrawableCompat.setTint(it, context.getColor(iconColor(category))) }
            ?: return null
        return SizedDrawable(tinted, (24f * context.resources.displayMetrics.density).toInt())
    }

    private fun iconColor(category: BriefContentCategory): Int = when (category) {
        BriefContentCategory.TOP_TWEET -> R.color.brief_icon_top_tweet
        BriefContentCategory.WORST_TWEET -> R.color.metric_red
        BriefContentCategory.TOP_FOLLOWERS, BriefContentCategory.TWEET_ACTIVITY -> R.color.oneui_accent
        BriefContentCategory.ACCOUNT_GOALS -> R.color.metric_green
        BriefContentCategory.FOLLOWERS, BriefContentCategory.SCHEDULED_TWEETS -> R.color.oneui_text_primary
    }

    private fun refreshBrief() {
        val context = requireContext().applicationContext
        val account = TwidgetStore.settings(context).username
        if (account.isNotBlank()) BriefStore.resetAi(context, account)
        AppExecutors.execute {
            if (account.isNotBlank()) BriefEngine.rebuild(context, account, force = true)
            TwidgetBriefWidget.updateAll(context)
        }
    }

    private fun spacerCategory() = PreferenceCategory(requireContext()).apply {
        isIconSpaceReserved = false
    }

    private class SizedDrawable(
        private val delegate: Drawable,
        private val sizePx: Int,
    ) : Drawable() {
        override fun draw(canvas: Canvas) = delegate.draw(canvas)

        override fun onBoundsChange(bounds: Rect) {
            delegate.bounds = bounds
        }

        override fun setAlpha(alpha: Int) {
            delegate.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            delegate.colorFilter = colorFilter
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = sizePx

        override fun getIntrinsicHeight(): Int = sizePx

        override fun isStateful(): Boolean = delegate.isStateful

        override fun onStateChange(state: IntArray): Boolean = delegate.setState(state)
    }
}
