package com.tjg.twidget.main

import com.tjg.twidget.core.AppLocales
import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ActivityClient
import com.tjg.twidget.analytics.AnalyticsBlendPolicy
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.BlendedAnalytics
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.XAnalyticsMovement
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefCardType
import com.tjg.twidget.brief.BriefEditorialSummary
import com.tjg.twidget.brief.BriefStrings
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.TwidgetBriefActivity
import com.tjg.twidget.data.AccountAverageSeries
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersCardBinder
import com.tjg.twidget.ui.MetricChartView
import com.tjg.twidget.ui.setCardCornerRadius
import dev.oneuiproject.oneui.widget.TipsCard
import dev.oneuiproject.oneui.R as OneUiIconR
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// Two grid footprints only: half-width and full-width. Charts are
// full-width cards with extra height.
internal enum class DashboardCardSize(val span: Int, val heightDp: Int) {
    HALF(1, 160),
    MILESTONE(2, 112),
    FULL(2, 160),
    CHART(2, 263),
    TOP_FOLLOWERS(2, 430),
    POST(2, 360),
}

internal enum class DashboardCardType(val id: String, val labelRes: Int, val size: DashboardCardSize) {
    FOLLOWER_RATIO("follower_ratio", R.string.follower_ratio, DashboardCardSize.HALF),
    POST_RATE("post_rate", R.string.post_rate, DashboardCardSize.HALF),
    LIKES_PER_POST("likes_per_post", R.string.likes_per_post, DashboardCardSize.HALF),
    ENGAGEMENT_RATE("engagement_rate", R.string.engagement_rate, DashboardCardSize.HALF),
    AVG_VIEWS("avg_views", R.string.avg_views, DashboardCardSize.HALF),
    TOTAL_VIEWS("total_views", R.string.total_views, DashboardCardSize.HALF),
    AVG_ENGAGEMENTS("avg_engagements", R.string.avg_engagements, DashboardCardSize.HALF),
    MEDIAN_ENGAGEMENTS("median_engagements", R.string.median_engagements, DashboardCardSize.HALF),
    X_IMPRESSIONS("x_impressions", R.string.x_impressions, DashboardCardSize.HALF),
    X_ENGAGEMENTS("x_engagements", R.string.x_engagements, DashboardCardSize.HALF),
    X_PROFILE_VISITS("x_profile_visits", R.string.x_profile_visits, DashboardCardSize.HALF),
    X_LIKES_RECEIVED("x_likes_received", R.string.x_likes_received, DashboardCardSize.HALF),
    MILESTONE("milestone", R.string.brief_title, DashboardCardSize.MILESTONE),
    DAILY_STREAK("daily_streak", R.string.daily_streak, DashboardCardSize.HALF),
    GROWTH_PACE("growth_pace", R.string.growth_pace, DashboardCardSize.HALF),
    BEST_DAY("best_day", R.string.best_recent_day, DashboardCardSize.HALF),
    MOMENTUM("momentum", R.string.momentum, DashboardCardSize.HALF),
    AUDIENCE_BALANCE("audience_balance", R.string.audience_balance, DashboardCardSize.HALF),
    ACCOUNT_HEALTH("account_health", R.string.account_health, DashboardCardSize.HALF),
    TOP_FOLLOWERS("top_followers", R.string.top_followers, DashboardCardSize.TOP_FOLLOWERS),
    ALL_TIME_POST("all_time_post", R.string.all_time_banger, DashboardCardSize.POST),
    BEST_POST("best_post_card", R.string.best_post, DashboardCardSize.POST),
    WORST_POST("worst_post_card", R.string.worst_post, DashboardCardSize.POST),
    FOLLOWERS("followers", R.string.followers, DashboardCardSize.CHART),
    FOLLOWING("following", R.string.following, DashboardCardSize.CHART),
    POSTS("posts", R.string.posts, DashboardCardSize.CHART),
    LIKES("likes", R.string.likes, DashboardCardSize.CHART);

    companion object {
        fun fromId(id: String): DashboardCardType? = entries.firstOrNull { it.id == id }
    }
}

internal data class InsightSpec(
    val label: String,
    val value: String,
    val detail: String,
    val accent: Int,
    val progress: Int? = null,
)

internal data class ChartBinding(
    val layoutRes: Int,
    val valueId: Int,
    val deltaId: Int,
    val chartId: Int,
    val value: String,
    val known: (HistorySample) -> Boolean,
    val selector: (HistorySample) -> Long,
)

internal class MainDashboardBinder(
    private val activity: MainActivity,
) {
    private val heavyTypeface: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.create("sec", Typeface.NORMAL), 700, false)
        } else {
            Typeface.create("sec", Typeface.BOLD)
        }
    }

    private val editModeController get() = activity.editModeController
    fun animateEditModeChange(enabled: Boolean, onTransitionStart: () -> Unit, render: () -> Unit) {
        render()
        val list = activity.findViewById<RecyclerView>(R.id.dashboard_content) ?: return
        list.post {
            onTransitionStart()
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return@post
            for (index in 0 until list.childCount) {
                val card = list.getChildAt(index) as? FrameLayout ?: continue
                card.animate().cancel()
                card.scaleX = if (enabled) 1f else EDIT_CARD_SCALE
                card.scaleY = card.scaleX
                card.animate().scaleX(if (enabled) EDIT_CARD_SCALE else 1f)
                    .scaleY(if (enabled) EDIT_CARD_SCALE else 1f)
                    .setDuration(140L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.25f, 0f, 0.25f, 1f))
                    .start()
            }
        }
    }

    fun bindContent(refreshRemote: Boolean = true) {
        val host = activity.findViewById<FrameLayout>(R.id.main_content_host)
        val page = host.findViewById<View>(R.id.main_account_page)
            ?: LayoutInflater.from(activity).inflate(R.layout.main_account_page, host, false)
                .also { host.addView(it, 0) }
        bindPage(page, activity.selectedAccount, refreshRemote)
    }

    private var reusableContent: View? = null
    private fun inflateCard(layout: Int): View = reusableContent ?: LayoutInflater.from(activity).inflate(layout, null, false)

    private var renderSnapshot: TwidgetStore.AccountSnapshot? = null

    private fun bindPage(page: View, account: String, refreshRemote: Boolean) {
        val snapshot = TwidgetStore.accountSnapshot(activity, account)
        renderSnapshot = snapshot
        val stats = snapshot.stats
        // Post analytics load from cache instantly; a background refresh below
        // repaints when fresh data arrives.
        activity.analytics = AnalyticsClient.cached(activity, account)
        // Daily samples drive the numbers; the chart list is the same week
        // bucketed down to a readable bar count. Analytics are fixed to the
        // weekly window — the old range chips are gone.
        val history = TwidgetStore.rangedHistory(snapshot.history, HistoryRange.WEEK)
        val chartHistory = TwidgetStore.chartHistory(snapshot.history, HistoryRange.WEEK)
        val fullHistory = snapshot.history
        activity.importedAnalytics = ImportedAnalyticsStore.recent(activity, account)
        val container = page.findViewById<RecyclerView>(R.id.dashboard_content) ?: return
        container.clipChildren = false
        container.clipToPadding = false
        container.setItemViewCacheSize(8)
        (container.parent as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        (page as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        activity.findViewById<ViewGroup>(R.id.main_content_host).apply {
            clipChildren = false
            clipToPadding = false
        }
        activity.findViewById<ViewGroup>(R.id.main_refresh).apply {
            clipChildren = false
            clipToPadding = false
            (parent as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
        }
        container.setOnDragListener(editModeController.dashboardDragListener)
        val cards = (editModeController.dragPreviewOrder ?: TwidgetStore.dashboardCards(activity))
            .mapNotNull(DashboardCardType::fromId)
            .filter { !it.requiresAnalyticsImport() || editModeController.hasAnalyticsImport() }
            .filter { it != DashboardCardType.MILESTONE || isDefaultAccount(account) }
        val scrollState = container.layoutManager?.onSaveInstanceState()
        container.layoutManager = (container.layoutManager as? GridLayoutManager)
            ?: GridLayoutManager(activity, DASHBOARD_GRID_COLUMNS).also { manager ->
                manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        (container.adapter as? DashboardAdapter)?.spanAt(position) ?: DASHBOARD_GRID_COLUMNS
                }
            }
        container.adapter = DashboardAdapter(
            cards = cards,
            account = account,
            stats = stats,
            history = history,
            chartHistory = chartHistory,
            fullHistory = fullHistory,
            showPrivateNotice = stats.isPrivate == true,
            showHistoryNotice = !TwidgetStore.isEstimateTipDismissed(activity) && chartHistory.any { it.estimated },
            showEditAction = !editModeController.editMode,
        )
        if (scrollState != null) container.layoutManager?.onRestoreInstanceState(scrollState)

        if (refreshRemote) {
            activity.syncController.maybeRefreshAnalytics(account)
            activity.syncController.maybeRefreshStreak(account)
        }
    }

    private sealed class DashboardItem {
        data object PrivateNotice : DashboardItem()
        data object HistoryNotice : DashboardItem()
        data class Card(val card: DashboardCardType) : DashboardItem()
        data class Placeholder(val card: DashboardCardType) : DashboardItem()
        data object EditAction : DashboardItem()
    }

    private inner class DashboardAdapter(
        private val cards: List<DashboardCardType>,
        private val account: String,
        private val stats: ProfileStats,
        private val history: List<HistorySample>,
        private val chartHistory: List<HistorySample>,
        private val fullHistory: List<HistorySample>,
        private val showPrivateNotice: Boolean,
        private val showHistoryNotice: Boolean,
        private val showEditAction: Boolean,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = buildList {
            if (showPrivateNotice) add(DashboardItem.PrivateNotice)
            if (showHistoryNotice) add(DashboardItem.HistoryNotice)
            cards.forEach { card ->
                add(if (card.id == editModeController.draggedCardId) DashboardItem.Placeholder(card) else DashboardItem.Card(card))
            }
            if (showEditAction) add(DashboardItem.EditAction)
        }

        override fun getItemCount(): Int = items.size
        override fun getItemViewType(position: Int): Int = when (items[position]) {
            DashboardItem.PrivateNotice -> ITEM_PRIVATE_NOTICE
            DashboardItem.HistoryNotice -> ITEM_HISTORY_NOTICE
            is DashboardItem.Card -> ITEM_CARD
            is DashboardItem.Placeholder -> ITEM_PLACEHOLDER
            DashboardItem.EditAction -> ITEM_EDIT_ACTION
        }

        fun spanAt(position: Int): Int = when (items.getOrNull(position)) {
            is DashboardItem.Card -> (items[position] as DashboardItem.Card).card.size.span
            is DashboardItem.Placeholder -> (items[position] as DashboardItem.Placeholder).card.size.span
            else -> DASHBOARD_GRID_COLUMNS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            ITEM_CARD, ITEM_PLACEHOLDER -> CardHolder(DashboardCardFrame())
            ITEM_PRIVATE_NOTICE -> TextHolder(TextView(activity))
            ITEM_EDIT_ACTION -> EditHolder(FrameLayout(activity).apply {
                addView(TextView(activity), FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ))
            })
            ITEM_HISTORY_NOTICE -> NoticeHolder(TipsCard(activity))
            else -> error("Unknown dashboard item type")
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                DashboardItem.PrivateNotice -> bindPrivateNotice((holder as TextHolder).text)
                DashboardItem.HistoryNotice -> bindHistoryNotice((holder as NoticeHolder).notice)
                DashboardItem.EditAction -> bindEditAction(holder as EditHolder)
                is DashboardItem.Card -> bindDashboardCard((holder as CardHolder).card, item.card)
                is DashboardItem.Placeholder -> bindDropPlaceholder((holder as CardHolder).card, item.card)
            }
        }

        private fun bindPrivateNotice(view: TextView) {
            view.text = activity.getString(R.string.private_account_notice)
            view.setTextColor(activity.getColor(R.color.oneui_text_secondary))
            view.textSize = 13f
            view.setPadding(activity.dp(26), activity.dp(8), activity.dp(26), activity.dp(10))
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        private fun bindHistoryNotice(notice: TipsCard) {
            notice.setTitle(activity.getString(R.string.estimated_notice_title))
            notice.setSummary(activity.getString(R.string.estimated_notice))
            notice.addButton(activity.getString(R.string.estimated_notice_dismiss)) {
                TwidgetStore.dismissEstimateTip(activity)
                activity.render(refreshRemote = false)
            }.minimumHeight = activity.dp(48)
            notice.background = AppCompatResources.getDrawable(activity, R.drawable.metric_card_bg)
            notice.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = activity.dp(10)
                topMargin = activity.dp(8)
                marginEnd = activity.dp(10)
                bottomMargin = activity.dp(5)
            }
        }

        private fun bindEditAction(holder: EditHolder) {
            val view = holder.text
            view.text = activity.getString(R.string.edit_dashboard)
            view.gravity = Gravity.CENTER
            view.minHeight = activity.dp(48)
            view.setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
            view.setTextColor(activity.getColor(R.color.oneui_text_secondary))
            view.textSize = 14f
            view.isClickable = true
            view.isFocusable = true
            view.background = GradientDrawable().apply {
                cornerRadius = activity.dp(40).toFloat()
                setColor(activity.getColor(R.color.dashboard_edit_button_bg))
            }
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_edit)?.apply {
                    setTint(activity.getColor(R.color.oneui_text_secondary))
                }, null, null, null,
            )
            view.compoundDrawablePadding = activity.dp(6)
            view.elevation = activity.dp(10).toFloat()
            view.setOnClickListener { editModeController.setEditMode(true) }
            holder.frame.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = activity.dp(28)
                bottomMargin = activity.dp(20)
            }
        }

        private fun bindDashboardCard(wrapper: DashboardCardFrame, card: DashboardCardType) {
            val content = createCardContent(card, account, stats, history, chartHistory, fullHistory)
            configureDashboardCardWrapper(wrapper, card, content)
            wrapper.layoutParams = cardLayoutParams(card)
        }

        private fun bindDropPlaceholder(wrapper: DashboardCardFrame, card: DashboardCardType) {
            wrapper.removeAllViews()
            wrapper.tag = DRAG_PLACEHOLDER_TAG
            wrapper.addView(createDropPlaceholder(card), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            wrapper.setOnDragListener(editModeController.dashboardDragListener)
            wrapper.layoutParams = cardLayoutParams(card)
        }

    }

    private class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text)
    private class NoticeHolder(val notice: TipsCard) : RecyclerView.ViewHolder(notice)
    private class CardHolder(val card: DashboardCardFrame) : RecyclerView.ViewHolder(card)
    private class EditHolder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        val text: TextView = frame.getChildAt(0) as TextView
    }

    // Net change across the whole visible range (last bucket minus first).
    private fun rangeDelta(history: List<HistorySample>, selector: (HistorySample) -> Long): Long {
        if (history.size < 2) return 0
        return selector(history.last()) - selector(history.first())
    }

    fun createCardPreview(card: DashboardCardType): View {
        val account = activity.selectedAccount
        val snapshot = TwidgetStore.accountSnapshot(activity, account)
        renderSnapshot = snapshot
        return createCardContent(
            card, account, snapshot.stats,
            TwidgetStore.rangedHistory(snapshot.history, HistoryRange.WEEK),
            TwidgetStore.chartHistory(snapshot.history, HistoryRange.WEEK),
            snapshot.history,
        )
    }

    private fun createCardContent(
        card: DashboardCardType, account: String, stats: ProfileStats,
        history: List<HistorySample>, chartHistory: List<HistorySample>, fullHistory: List<HistorySample>,
    ): View = when {
        card == DashboardCardType.TOP_FOLLOWERS -> createTopFollowersCard(account)
        card in POST_CARD_TYPES -> activity.postAnalyticsBinder.createGridCard(card, account)
        card.size == DashboardCardSize.CHART -> createChartCard(card, account, stats, chartHistory, fullHistory)
        card == DashboardCardType.MILESTONE -> createBriefCard(stats, account)
        card == DashboardCardType.DAILY_STREAK -> createStreakCard(stats)
        else -> createInsightCard(card, stats, history)
    }

    private fun createInsightCard(card: DashboardCardType, stats: ProfileStats, history: List<HistorySample>): View {
        val spec = insightSpec(card, stats, history)
        return inflateCard(R.layout.metric_card_small_stat).apply {
            findViewById<ImageView>(R.id.metric_platform_icon).apply {
                setImageResource(com.tjg.twidget.ui.AppAppearance.logoDrawable(context))
                imageTintList = ColorStateList.valueOf(activity.getColor(R.color.oneui_text_secondary))
            }
            findViewById<TextView>(R.id.metric_label).text = spec.label
            findViewById<TextView>(R.id.followers_value).apply {
                text = spec.value
                com.tjg.twidget.ui.TwidgetFonts.setRole(this, com.tjg.twidget.ui.TwidgetFonts.Role.DASHBOARD_VALUE)
            }
            findViewById<TextView>(R.id.stat_detail).apply {
                text = spec.detail
                visibility = if (spec.detail.isBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun createBriefCard(stats: ProfileStats, account: String): View {
        val root = inflateCard(R.layout.brief_dashboard_card)
        val snapshot = BriefEngine.rebuild(activity, account)
        val presentation = BriefStore.presentation(activity, account, snapshot)
        val summary = presentation?.summary() ?: run {
            BriefEditorialSummary(
                activity.getString(R.string.brief_widget_empty_title),
                activity.getString(R.string.brief_widget_empty_body),
                activity.getString(R.string.brief_widget_empty_body),
            )
        }
        val hero = snapshot.cards.firstOrNull() ?: com.tjg.twidget.brief.BriefCard(
            id = "empty",
            type = BriefCardType.SUMMARY,
            title = activity.getString(R.string.brief_widget_empty_title),
            body = activity.getString(R.string.brief_categories_empty_body),
            score = 0,
        )
        val iconRes = when (presentation?.heroType ?: hero.type) {
            BriefCardType.MILESTONE -> R.drawable.ic_milestone_goals
            BriefCardType.STREAK -> R.drawable.ic_streak_fire
            BriefCardType.TOP_FOLLOWER -> OneUiIconR.drawable.ic_oui_community
            else -> null
        }
        root.findViewById<ImageView>(R.id.brief_dashboard_icon).apply {
            visibility = if (iconRes == null) View.GONE else View.VISIBLE
            iconRes?.let {
                setImageDrawable(AppCompatResources.getDrawable(activity, it))
                imageTintList = ColorStateList.valueOf(activity.getColor(R.color.oneui_text_primary))
            }
        }
        root.findViewById<LinearLayout>(R.id.brief_dashboard_copy).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = activity.dp(
                if (iconRes == null) 4 else 12,
            )
        }
        root.findViewById<TextView>(R.id.brief_dashboard_title).text = summary.title
        root.findViewById<TextView>(R.id.brief_dashboard_message).text = summary.shortDescription
        root.background = MilestoneCardBackgroundDrawable(
            glowColor = activity.getColor(R.color.brief_dashboard_glow),
            surfaceColor = activity.getColor(R.color.oneui_card_bg),
            radiusPx = activity.dp(28).toFloat(),
        )
        root.contentDescription =
            "${activity.getString(R.string.brief_title)}. ${summary.title}. ${summary.shortDescription}"
        root.setOnClickListener {
            if (!editModeController.editMode && isDefaultAccount(account)) {
                BriefSettingsStore.setEnabled(activity, true)
                activity.startActivity(TwidgetBriefActivity.intent(activity, account))
            }
        }
        return root
    }

    private fun isDefaultAccount(account: String): Boolean =
        account.equals(TwidgetStore.settings(activity).username, ignoreCase = true)

    private fun createStreakCard(stats: ProfileStats): View =
        StreakCardFactory.create(activity, ActivityClient.snapshot(activity, stats.userName), existing = reusableContent as? LinearLayout)

    private fun createTopFollowersCard(account: String): View {
        return TopFollowersCardBinder(
            activity = activity,
            requestNotificationPermission = { activity.requestTopFollowersNotificationPermission() },
        ).create(account)
    }

    private fun createChartCard(
        card: DashboardCardType,
        account: String,
        stats: ProfileStats,
        chartHistory: List<HistorySample>,
        fullHistory: List<HistorySample>,
    ): View {
        val (layoutRes, valueId, deltaId, chartId, value, known, selector) = when (card) {
            DashboardCardType.FOLLOWERS -> ChartBinding(
                R.layout.metric_card_followers,
                R.id.followers_value,
                R.id.followers_delta,
                R.id.followers_chart,
                if (stats.followersKnown) fullCount(stats.followersCount) else "--",
                { it.followersKnown },
            ) { it.followers }
            DashboardCardType.FOLLOWING -> ChartBinding(
                R.layout.metric_card_following,
                R.id.following_value,
                R.id.following_delta,
                R.id.following_chart,
                if (stats.followingKnown) fullCount(stats.followingsCount) else "--",
                { it.followingKnown },
            ) { it.following }
            DashboardCardType.POSTS -> ChartBinding(
                R.layout.metric_card_posts,
                R.id.posts_value,
                R.id.posts_delta,
                R.id.posts_chart,
                if (stats.postsKnown) fullCount(stats.statusesCount) else "--",
                { it.postsKnown },
            ) { it.posts }
            DashboardCardType.LIKES -> ChartBinding(
                R.layout.metric_card_likes,
                R.id.likes_value,
                R.id.likes_delta,
                R.id.likes_chart,
                if (stats.likesKnown) fullCount(stats.likeCount) else "--",
                { it.likesKnown },
            ) { it.likes }
            else -> error("Compact cards do not have chart layouts.")
        }
        return inflateCard(layoutRes).also { root ->
            root.findViewById<ImageView>(R.id.metric_platform_icon).setImageResource(com.tjg.twidget.ui.AppAppearance.logoDrawable(activity))
            bindMetric(
                root,
                valueId,
                deltaId,
                chartId,
                value,
                renderSnapshot?.todayDelta(known, selector) ?: 0,
                chartHistory.filter(known),
                fullHistory.filter(known),
                selector,
                allowSparseAverage = card == DashboardCardType.FOLLOWERS &&
                    fullHistory.any { it.imported && it.followersKnown },
            )
            if (METRIC_HISTORY_DRILL_DOWN_ENABLED) {
                val openHistory = {
                    if (!editModeController.editMode) {
                        activity.startActivity(MetricChartActivity.intent(activity, account, card.id))
                    }
                }
                root.setOnClickListener { openHistory() }
                root.findViewById<MetricChartView>(chartId)?.onChartTapListener = openHistory
            }
        }
    }

    private fun createDropPlaceholder(card: DashboardCardType): View =
        FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = activity.dp(22).toFloat()
                val neutral = activity.getColor(R.color.oneui_text_secondary)
                setColor((neutral and 0x00ffffff) or 0x18000000)
            }
            alpha = 0.75f
            contentDescription = activity.getString(card.labelRes)
        }

    private inner class DashboardCardFrame : FrameLayout(activity) {
            private val holdFeedback = com.tjg.twidget.ui.CardHoldFeedback(this, enabled = { !editModeController.editMode })
            private var touchingRemoveButton = false
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (!editModeController.editMode) return super.onInterceptTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val remove = (0 until childCount).map { getChildAt(it) }.filterIsInstance<ImageButton>().firstOrNull()
                    touchingRemoveButton = remove != null && event.x >= remove.left && event.x < remove.right &&
                        event.y >= remove.top && event.y < remove.bottom
                }
                // The wrapper owns pickup gestures. Card links, buttons and
                // charts must never receive this stream while editing.
                return !touchingRemoveButton
            }
            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                holdFeedback.onTouch(event) || super.dispatchTouchEvent(event)
            override fun onDetachedFromWindow() {
                holdFeedback.detach()
                super.onDetachedFromWindow()
            }
    }

    private fun configureDashboardCardWrapper(wrapper: DashboardCardFrame, card: DashboardCardType, content: View) {
            wrapper.removeAllViews()
            wrapper.tag = card.id
            wrapper.scaleX = if (editModeController.editMode) EDIT_CARD_SCALE else 1f
            wrapper.scaleY = wrapper.scaleX
            val longPressHandler = object : View.OnLongClickListener {
                override fun onLongClick(view: View): Boolean = handleDashboardCardLongPress(card, wrapper)

                // API 34+ uses our action-specific feedback; older releases retain automatic pickup.
                override fun onLongClickUseDefaultHapticFeedback(view: View): Boolean = false
            }
            wrapper.addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (card.size == DashboardCardSize.POST) {
                    FrameLayout.LayoutParams.WRAP_CONTENT
                } else {
                    FrameLayout.LayoutParams.MATCH_PARENT
                },
            ))
            if (editModeController.editMode) {
                content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                wrapper.clipChildren = false
                wrapper.clipToPadding = false
                (content as? dev.oneuiproject.oneui.delegates.ViewRoundedCorner)?.roundedCorners = 0
                val radius = activity.dp(28).toFloat()
                content.background?.mutate()?.setCardCornerRadius(radius)
                val inset = activity.dp(com.tjg.twidget.ui.CardShadow.PADDING_DP)
                wrapper.addView(com.tjg.twidget.ui.CardShadowView(activity, radius), 0,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                        setMargins(-inset, -inset, -inset, -inset)
                    })
                wrapper.addView(View(activity).apply {
                    tag = EDIT_BORDER_TAG
                    background = GradientDrawable().apply {
                        cornerRadius = radius
                        setColor(Color.TRANSPARENT)
                        setStroke(activity.dp(1), activity.getColor(R.color.dashboard_edit_border))
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            wrapper.setOnLongClickListener(longPressHandler)
            attachCardLongPress(content, longPressHandler)
            wrapper.setOnDragListener(editModeController.dashboardDragListener)
            if (editModeController.editMode) {
                wrapper.addView(removeCardButton(card), FrameLayout.LayoutParams(activity.dp(30), activity.dp(30), Gravity.TOP or Gravity.END).apply {
                    topMargin = activity.dp(10)
                    marginEnd = activity.dp(10)
                })
            }
    }

    fun showDropBounce(cardId: String) {
        val grid = activity.findViewById<RecyclerView>(R.id.dashboard_content) ?: return
        val card = (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { it.tag == cardId } ?: return
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        card.post {
            card.animate().cancel()
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    if (!card.isAttachedToWindow) { cancel(); return@addUpdateListener }
                    val time = it.animatedValue as Float
                    val scale = EDIT_CARD_SCALE * (1.0 - 0.035 * kotlin.math.exp(-7.0 * time) * kotlin.math.cos(18.0 * time)).toFloat()
                    card.scaleX = scale
                    card.scaleY = scale
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        card.scaleX = EDIT_CARD_SCALE
                        card.scaleY = EDIT_CARD_SCALE
                    }
                })
                start()
            }
        }
    }

    private fun handleDashboardCardLongPress(card: DashboardCardType, dragView: View): Boolean {
        if (!editModeController.editMode) {
            // Entry feedback is synchronized with the replacement card's first animation frame.
            editModeController.setEditMode(true)
        } else {
            editModeController.draggedCardId = card.id
            editModeController.dragPreviewOrder = TwidgetStore.dashboardCards(activity)
            editModeController.dragSourceView = dragView
            val dragShadow = object : View.DragShadowBuilder(dragView) {
                override fun onDrawShadow(canvas: Canvas) {
                    val layer = canvas.saveLayerAlpha(0f, 0f, dragView.width.toFloat(), dragView.height.toFloat(), 180)
                    val radius = (0 until ((dragView as? ViewGroup)?.childCount ?: 0))
                        .mapNotNull { ((dragView as ViewGroup).getChildAt(it).background as? GradientDrawable)?.cornerRadius }
                        .firstOrNull { it > 0f } ?: activity.dp(28).toFloat()
                    canvas.clipPath(Path().apply {
                        addRoundRect(0f, 0f, dragView.width.toFloat(), dragView.height.toFloat(),
                            radius, radius, Path.Direction.CW)
                    })
                    super.onDrawShadow(canvas)
                    canvas.restoreToCount(layer)
                }
            }
            val started = dragView.startDragAndDrop(
                ClipData.newPlainText("dashboard_card", card.id),
                dragShadow,
                card.id,
                0,
            )
            if (started) {
                com.tjg.twidget.ui.TwidgetHaptics.dragPickup(dragView)
                moveDropPlaceholder(card, card.id)
                editModeController.startDragHoldFeedback()
            } else {
                editModeController.finishDashboardDrag(commit = false)
            }
        }
        return true
    }

    private fun attachCardLongPress(
        view: View,
        listener: View.OnLongClickListener,
        isContentRoot: Boolean = true,
    ) {
        // Making every descendant long-clickable also makes plain TextViews
        // and ImageViews consume taps before an interactive row can receive
        // them. Keep edit-mode long press on the card root and on controls
        // that already own clicks; decorative descendants remain transparent
        // to their row's touch target.
        if (isContentRoot || view.isClickable) {
            view.setOnLongClickListener(listener)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachCardLongPress(view.getChildAt(index), listener, isContentRoot = false)
            }
        }
    }

    private fun removeCardButton(card: DashboardCardType): ImageButton =
        ImageButton(activity).apply {
            setImageResource(OneUiIconR.drawable.ic_oui_minus)
            imageTintList = ColorStateList.valueOf(activity.getColor(R.color.metric_red))
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activity.getColor(R.color.dashboard_remove_button_bg))
                setStroke(activity.dp(1), activity.getColor(R.color.dashboard_edit_border))
            }
            elevation = activity.dp(12).toFloat()
            if (Build.VERSION.SDK_INT >= 28) {
                outlineAmbientShadowColor = 0x40000000
                outlineSpotShadowColor = 0x40000000
            }
            setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3))
            contentDescription = activity.getString(R.string.delete)
            setOnClickListener { editModeController.removeDashboardCard(card.id) }
        }

    private fun fullCount(value: Long): String =
        NumberFormat.getIntegerInstance(AppLocales.applicationLocale()).format(value)

    private fun bindMetric(
        root: View,
        valueId: Int,
        deltaId: Int,
        chartId: Int,
        value: String,
        delta: Long,
        history: List<HistorySample>,
        fullHistory: List<HistorySample>,
        selector: (HistorySample) -> Long,
        allowSparseAverage: Boolean,
    ) {
        root.findViewById<TextView>(valueId)?.apply {
            text = value
            // textStyle="bold" renders Samsung's lighter bold cut; force the
            // same true 700 weight the insight cards use.
            typeface = heavyTypeface
        }
        root.findViewById<TextView>(deltaId)?.apply {
            com.tjg.twidget.ui.TwidgetFonts.setRole(this, com.tjg.twidget.ui.TwidgetFonts.Role.CHART_DELTA)
            text = if (delta == 0L) "" else TwidgetStore.signedNumber(delta)
            setTextColor(if (delta < 0) activity.getColor(R.color.metric_red) else activity.getColor(R.color.metric_green))
            visibility = if (delta == 0L) View.GONE else View.VISIBLE
        }
        root.findViewById<MetricChartView>(chartId)?.apply {
            setData(history, selector)
            setAverageSeries(AccountAverageSeries.values(fullHistory, history, selector, allowSparseAverage))
            root.post {
                (root.parent as? FrameLayout)?.let { host -> setTooltipOverlayHost(host) }
            }
        }
    }

    private fun insightSpec(card: DashboardCardType, stats: ProfileStats, history: List<HistorySample>): InsightSpec {
        val followersDelta = rangeDelta(history) { it.followers }
        return when (card) {
            DashboardCardType.FOLLOWER_RATIO -> {
                val diff = stats.followersCount - stats.followingsCount
                InsightSpec(
                    label = activity.getString(R.string.follower_ratio),
                    value = if (stats.followersKnown && stats.followingKnown) {
                        decimal(stats.followersCount.toDouble() / stats.followingsCount.coerceAtLeast(1), "x")
                    } else "--",
                    detail = if (!stats.followersKnown || !stats.followingKnown) {
                        activity.getString(R.string.unknown_profile_status)
                    } else if (diff >= 0) {
                        activity.getString(R.string.more_followers_than_following, TwidgetStore.compactNumber(diff))
                    } else {
                        activity.getString(R.string.fewer_followers_than_following, TwidgetStore.compactNumber(-diff))
                    },
                    accent = activity.getColor(R.color.oneui_accent),
                )
            }
            DashboardCardType.POST_RATE -> InsightSpec(
                label = activity.getString(R.string.post_rate),
                value = history.filter { it.postsKnown }.let { known ->
                    if (known.size >= 2) decimal(dailyAverage(known) { it.posts }.coerceAtLeast(0.0), "") else "--"
                },
                detail = history.filter { it.postsKnown }.let { known ->
                    if (known.size >= 2) {
                        activity.getString(R.string.per_range, TwidgetStore.signedNumber(rangeDelta(known) { it.posts }))
                    } else activity.getString(R.string.unknown_profile_status)
                },
                accent = activity.getColor(R.color.metric_green),
            )
            DashboardCardType.LIKES_PER_POST -> InsightSpec(
                label = activity.getString(R.string.likes_per_post),
                value = if (stats.likesKnown && stats.postsKnown) {
                    decimal(stats.likeCount.toDouble() / stats.statusesCount.coerceAtLeast(1), "")
                } else "--",
                detail = if (stats.likesKnown) {
                    "${TwidgetStore.compactNumber(stats.likeCount)} ${activity.getString(R.string.likes).lowercase(Locale.US)}"
                } else activity.getString(R.string.unknown_profile_status),
                accent = activity.getColor(R.color.oneui_accent),
            )
            DashboardCardType.MILESTONE -> {
                val milestoneSettings = TwidgetStore.milestoneSettings(activity, stats.userName)
                val spec = MilestonePolicy.resolveCardSpec(
                    followersCount = stats.followersCount,
                    followersKnown = stats.followersKnown,
                    settings = milestoneSettings,
                    autoNextMilestone = ::nextMilestone,
                    autoPreviousMilestone = ::previousMilestone,
                    compactNumber = TwidgetStore::compactNumber,
                    goalReachedText = activity.getString(R.string.milestone_goal_reached),
                    unknownFollowersText = activity.getString(R.string.milestone_unknown_followers),
                    toNextMilestone = { remaining, target ->
                        activity.getString(R.string.to_next_milestone, remaining, target)
                    },
                    milestoneLabel = activity.getString(R.string.milestone_progress),
                )
                InsightSpec(
                    label = spec.label,
                    value = spec.value,
                    detail = spec.detail,
                    accent = activity.getColor(R.color.oneui_accent),
                    progress = spec.progress,
                )
            }
            DashboardCardType.DAILY_STREAK -> {
                val streak = ActivityClient.snapshot(activity, stats.userName)
                InsightSpec(
                    label = activity.getString(R.string.daily_streak),
                    value = if (streak.streak > 0) {
                        activity.resources.getQuantityString(R.plurals.daily_streak_days, streak.streak, streak.streak)
                    } else {
                        activity.getString(R.string.daily_streak_none)
                    },
                    detail = when {
                        streak.activeToday -> activity.getString(R.string.daily_streak_active_today)
                        streak.streak > 0 && streak.lastActiveDay != null ->
                            activity.getString(R.string.daily_streak_last_active, streak.lastActiveDay)
                        else -> activity.getString(R.string.daily_streak_keep_going)
                    },
                    accent = if (streak.streak > 0) {
                        activity.getColor(R.color.metric_green)
                    } else {
                        activity.getColor(R.color.oneui_text_secondary)
                    },
                )
            }
            DashboardCardType.GROWTH_PACE -> {
                val daily = dailyAverage(history) { it.followers }
                InsightSpec(
                    label = activity.getString(R.string.insight_growth),
                    value = TwidgetStore.signedNumber(followersDelta),
                    detail = activity.getString(R.string.per_day, signedDecimal(daily)),
                    accent = if (followersDelta < 0) activity.getColor(R.color.metric_red) else activity.getColor(R.color.metric_green),
                )
            }
            DashboardCardType.BEST_DAY -> {
                val best = bestRecentDay(history)
                InsightSpec(
                    label = activity.getString(R.string.insight_best_day),
                    value = if (best == null) "--" else TwidgetStore.signedNumber(best.second),
                    detail = best?.first ?: activity.getString(R.string.no_recent_gain),
                    accent = activity.getColor(R.color.metric_green),
                )
            }
            DashboardCardType.MOMENTUM -> {
                val momentum = momentum(history)
                InsightSpec(
                    label = activity.getString(R.string.momentum),
                    value = momentum.first,
                    detail = activity.getString(R.string.per_range, TwidgetStore.signedNumber(followersDelta)),
                    accent = momentum.second,
                )
            }
            DashboardCardType.AUDIENCE_BALANCE -> {
                val ratio = stats.followersCount.toDouble() / stats.followingsCount.coerceAtLeast(1)
                InsightSpec(
                    label = activity.getString(R.string.insight_balance),
                    value = if (ratio >= 1.0) {
                        String.format(AppLocales.applicationLocale(), "%.1f:1", ratio)
                    } else {
                        String.format(AppLocales.applicationLocale(), "1:%.1f", 1.0 / ratio.coerceAtLeast(0.01))
                    },
                    detail = "${TwidgetStore.compactNumber(stats.followersCount)} / ${TwidgetStore.compactNumber(stats.followingsCount)}",
                    accent = activity.getColor(R.color.oneui_accent),
                )
            }
            DashboardCardType.ACCOUNT_HEALTH -> {
                // Never claim Public unless the API explicitly said so.
                InsightSpec(
                    label = activity.getString(R.string.insight_health),
                    value = when {
                        stats.isVerified == true -> activity.getString(R.string.verified)
                        stats.isPrivate == true -> activity.getString(R.string.private_profile)
                        stats.isPrivate == false -> activity.getString(R.string.public_profile)
                        else -> "--"
                    },
                    detail = when {
                        stats.isVerified == true && stats.isPrivate == true -> activity.getString(R.string.verified_private)
                        stats.isVerified == true && stats.isPrivate == false -> activity.getString(R.string.verified_public)
                        stats.isVerified == true -> activity.getString(R.string.verified)
                        stats.isPrivate == true -> activity.getString(R.string.private_unverified)
                        stats.isPrivate == false -> activity.getString(R.string.public_unverified)
                        else -> activity.getString(R.string.unknown_profile_status)
                    },
                    accent = when {
                        stats.isPrivate == true -> activity.getColor(R.color.metric_red)
                        stats.isVerified == true || stats.isPrivate == false -> activity.getColor(R.color.metric_green)
                        else -> activity.getColor(R.color.oneui_text_secondary)
                    },
                )
            }
            DashboardCardType.ENGAGEMENT_RATE -> blendedAnalyticsSpec(
                activity.getString(R.string.engagement_rate),
                activity.getColor(R.color.oneui_accent),
                { blend -> blend.engagementRate?.let(::percent) },
                { blend -> blend.usesImportedRate },
            )
            DashboardCardType.AVG_VIEWS -> blendedAnalyticsSpec(
                activity.getString(R.string.avg_views),
                activity.getColor(R.color.metric_green),
                { blend -> blend.avgViews?.roundToLong()?.let(TwidgetStore::compactNumber) },
                { blend -> blend.usesImportedViews },
            )
            DashboardCardType.TOTAL_VIEWS -> analyticsSpec(
                activity.getString(R.string.total_views),
                activity.getColor(R.color.oneui_accent),
                { TwidgetStore.compactNumber(it.totalViews) },
                { analyticsCoverage(it) },
            )
            DashboardCardType.AVG_ENGAGEMENTS -> blendedAnalyticsSpec(
                activity.getString(R.string.avg_engagements),
                activity.getColor(R.color.metric_green),
                { blend -> blend.avgEngagements?.roundToLong()?.let(TwidgetStore::compactNumber) },
                { blend -> blend.usesImportedEngagements },
            )
            DashboardCardType.MEDIAN_ENGAGEMENTS -> analyticsSpec(
                activity.getString(R.string.median_engagements),
                activity.getColor(R.color.oneui_accent),
                { TwidgetStore.compactNumber(it.medianEngagements.roundToLong()) },
                { analyticsCoverage(it) },
            )
            DashboardCardType.X_IMPRESSIONS -> importedAnalyticsSpec(
                activity.getString(R.string.x_impressions),
                activity.getColor(R.color.oneui_accent),
            ) { it.impressions }
            DashboardCardType.X_ENGAGEMENTS -> importedAnalyticsSpec(
                activity.getString(R.string.x_engagements),
                activity.getColor(R.color.metric_green),
            ) { it.engagements }
            DashboardCardType.X_PROFILE_VISITS -> importedAnalyticsSpec(
                activity.getString(R.string.x_profile_visits),
                activity.getColor(R.color.oneui_accent),
            ) { it.profileVisits }
            DashboardCardType.X_LIKES_RECEIVED -> importedAnalyticsSpec(
                activity.getString(R.string.x_likes_received),
                activity.getColor(R.color.metric_green),
            ) { it.likes }
            else -> error("Chart cards do not have insight specs.")
        }
    }

    private fun importedAnalyticsSpec(
        label: String,
        accent: Int,
        selector: (XAnalyticsMovement) -> Long?,
    ): InsightSpec {
        val values = activity.importedAnalytics.mapNotNull(selector)
        return InsightSpec(
            label = label,
            value = values.takeIf { it.isNotEmpty() }?.sum()?.let(TwidgetStore::compactNumber) ?: "--",
            detail = if (values.isEmpty()) {
                activity.getString(R.string.import_x_analytics_hint)
            } else {
                activity.getString(R.string.x_analytics_days, values.size)
            },
            accent = accent,
        )
    }

    private fun blendedAnalyticsSpec(
        label: String,
        accent: Int,
        value: (BlendedAnalytics) -> String?,
        usesImported: (BlendedAnalytics) -> Boolean,
    ): InsightSpec {
        val blend = AnalyticsBlendPolicy.blend(activity.analytics, activity.importedAnalytics)
        return InsightSpec(
            label = label,
            value = value(blend) ?: "--",
            detail = when {
                usesImported(blend) -> activity.getString(
                    R.string.analytics_blended_coverage,
                    blend.livePosts,
                    blend.importedDays,
                )
                activity.analytics != null -> analyticsCoverage(requireNotNull(activity.analytics))
                else -> activity.getString(R.string.analytics_syncing)
            },
            accent = accent,
        )
    }

    private fun analyticsCoverage(data: PostAnalytics): String =
        if (data.isSampled) {
            activity.getString(R.string.posts_from_capped_status_sample, data.postsAnalyzed, data.statusesInspected)
        } else {
            activity.getString(R.string.across_posts, data.postsAnalyzed)
        }

    // Analytics cards fall back to a placeholder until the timeline fetch lands.
    private fun analyticsSpec(
        label: String,
        accent: Int,
        value: (PostAnalytics) -> String,
        detail: (PostAnalytics) -> String,
    ): InsightSpec {
        val data = activity.analytics
        return InsightSpec(
            label = label,
            value = data?.let(value) ?: "--",
            detail = data?.let(detail) ?: activity.getString(R.string.analytics_syncing),
            accent = accent,
        )
    }

    private fun percent(fraction: Double): String =
        AppLocales.percent(fraction, 2)

    // Average per real elapsed day — samples can have gaps, so count days
    // between the first and last sample rather than counting samples.
    private fun dailyAverage(history: List<HistorySample>, selector: (HistorySample) -> Long): Double {
        if (history.size < 2) return 0.0
        val days = ((history.last().timestamp - history.first().timestamp) / DAY_MILLIS).coerceAtLeast(1)
        return rangeDelta(history, selector).toDouble() / days
    }

    private fun bestRecentDay(history: List<HistorySample>): Pair<String, Long>? =
        history.zipWithNext()
            .map { (previous, current) -> current.dayLabel to (current.followers - previous.followers) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }

    private fun momentum(history: List<HistorySample>): Pair<String, Int> {
        if (history.size < 4) return activity.getString(R.string.flat) to activity.getColor(R.color.oneui_text_secondary)
        val middle = history.lastIndex / 2
        val firstHalf = history[middle].followers - history.first().followers
        val secondHalf = history.last().followers - history[middle].followers
        val threshold = maxOf(2L, (abs(firstHalf) * 0.25).roundToLong())
        return when {
            secondHalf > firstHalf + threshold -> activity.getString(R.string.accelerating) to activity.getColor(R.color.metric_green)
            secondHalf < firstHalf - threshold -> activity.getString(R.string.cooling) to activity.getColor(R.color.metric_red)
            firstHalf == 0L && secondHalf == 0L -> activity.getString(R.string.flat) to activity.getColor(R.color.oneui_text_secondary)
            else -> "Steady" to activity.getColor(R.color.oneui_accent)
        }
    }

    private fun nextMilestone(value: Long): Long {
        var step = 100L
        while (value >= step * 10) step *= 10
        return ((value / step) + 1) * step
    }

    private fun previousMilestone(milestone: Long): Long {
        var step = 100L
        while (milestone > step * 10) step *= 10
        return milestone - step
    }

    private fun signedDecimal(value: Double): String =
        if (abs(value) >= 10.0) {
            TwidgetStore.signedNumber(value.roundToLong())
        } else {
            val sign = if (value > 0) "+" else ""
            String.format(AppLocales.applicationLocale(), "%s%.1f", sign, value)
        }

    fun moveDropPlaceholder(card: DashboardCardType, targetId: String, after: Boolean = false) {
        activity.render(refreshRemote = false)
    }

    private fun cardLayoutParams(card: DashboardCardType): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (card.size == DashboardCardSize.POST) ViewGroup.LayoutParams.WRAP_CONTENT else activity.dp(card.size.heightDp),
        ).apply { setMargins(activity.dp(5), activity.dp(5), activity.dp(5), activity.dp(5)) }

    private fun decimal(value: Double, suffix: String): String =
        String.format(AppLocales.applicationLocale(), "%.1f%s", value, suffix)

    private companion object {
        private const val EDIT_BORDER_TAG = "dashboard_edit_border"
        private const val EDIT_CARD_SCALE = 0.97f
        private const val ITEM_PRIVATE_NOTICE = 0
        private const val ITEM_HISTORY_NOTICE = 1
        private const val ITEM_CARD = 2
        private const val ITEM_PLACEHOLDER = 3
        private const val ITEM_EDIT_ACTION = 4
        private const val DASHBOARD_GRID_COLUMNS = 2
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val DRAG_PLACEHOLDER_TAG = "dashboard_drop_placeholder"
        private val POST_CARD_TYPES = setOf(
            DashboardCardType.ALL_TIME_POST,
            DashboardCardType.BEST_POST,
            DashboardCardType.WORST_POST,
        )
    }
}

// Keep the compact dashboard charts visible in 1.2, but do not expose the
// unfinished full-history page from Followers, Following, Posts, or Likes.
private const val METRIC_HISTORY_DRILL_DOWN_ENABLED = false

internal fun DashboardCardType.requiresAnalyticsImport(): Boolean = when (this) {
    DashboardCardType.X_IMPRESSIONS,
    DashboardCardType.X_ENGAGEMENTS,
    DashboardCardType.X_PROFILE_VISITS,
    DashboardCardType.X_LIKES_RECEIVED,
    -> true
    else -> false
}
