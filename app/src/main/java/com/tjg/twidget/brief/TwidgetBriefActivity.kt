package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.AccountAverageSeries
import com.tjg.twidget.data.DailyStreakStore
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersBrowseActivity
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.main.MilestoneArcView
import com.tjg.twidget.main.MilestoneCardBackgroundDrawable
import com.tjg.twidget.main.MilestoneGoalActivity
import com.tjg.twidget.main.MilestoneGoalStore
import com.tjg.twidget.main.MilestoneMetricResolver
import com.tjg.twidget.main.MilestonePerformanceState
import com.tjg.twidget.main.MilestonePolicy
import com.tjg.twidget.main.StreakCardFactory
import com.tjg.twidget.schedule.LocalUriMedia
import com.tjg.twidget.schedule.PublicUrlMedia
import com.tjg.twidget.schedule.ScheduleComposeActivity
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStore
import com.tjg.twidget.settings.BriefSettingsActivity
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.MetricChartView
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.startRightSidePopOverActivity
import dev.oneuiproject.oneui.R as OneUiIconR
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TwidgetBriefActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private var renderedSnapshot: BriefSnapshot? = null
    private var localStatus = BriefLocalStatus.UNAVAILABLE
    private var debugScenario: BriefDebugScenario = BriefDebugScenario.REAL
    private var reloadOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_twidget_brief)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')
            .ifBlank { TwidgetStore.settings(this).username }
        val defaultAccount = TwidgetStore.settings(this).username.trim().trimStart('@')
        if (username.isBlank() || !username.equals(defaultAccount, ignoreCase = true)) {
            finish()
            return
        }
        if (TwidgetStore.debugMenuUnlocked(this)) {
            debugScenario = BriefDebugScenario.fromStorageId(
                intent.getStringExtra(EXTRA_DEBUG_SCENARIO),
            )
        }
        BriefSettingsStore.setEnabled(this, true)

        bindChrome()
        loadBrief()
    }

    override fun onResume() {
        super.onResume()
        if (reloadOnResume) {
            reloadOnResume = false
            loadBrief(force = true)
        }
    }

    private fun loadBrief(force: Boolean = false) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    debugScenario.snapshot(
                        BriefEngine.rebuild(this@TwidgetBriefActivity, username, force),
                    )
                }
                val result = if (debugScenario == BriefDebugScenario.REAL) {
                    BriefAiCoordinator.enrich(this@TwidgetBriefActivity, source)
                } else {
                    BriefAiResult(source, BriefLocalStatus.UNAVAILABLE)
                }
                render(result.snapshot)
                localStatus = result.localStatus
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        findViewById<View>(R.id.brief_scroll).visibility = if (loading) View.GONE else View.VISIBLE
        findViewById<LottieAnimationView>(R.id.brief_loading).apply {
            visibility = if (loading) View.VISIBLE else View.GONE
            if (loading) playAnimation() else cancelAnimation()
        }
        findViewById<ImageButton>(R.id.brief_footer_info).isEnabled = !loading
        findViewById<ImageButton>(R.id.brief_footer_settings).isEnabled = !loading
    }

    private fun bindChrome() {
        applyEdgeToEdgeInsets(findViewById(R.id.brief_root))
        val tint = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
        findViewById<ImageButton>(R.id.brief_back).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_back))
            imageTintList = tint
            setOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.brief_footer_account).text = "@$username"
        findViewById<ImageButton>(R.id.brief_footer_info).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_info_outline))
            imageTintList = tint
            setOnClickListener { showProviderInfo() }
        }
        findViewById<ImageButton>(R.id.brief_footer_settings).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_settings_outline))
            imageTintList = tint
            setOnClickListener {
                startRightSidePopOverActivity(
                    Intent(this@TwidgetBriefActivity, BriefSettingsActivity::class.java),
                )
            }
        }
    }

    private fun followerChartCard(card: BriefCard): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.brief_card_background)
        setPadding(0, dp(16), 0, 0)
        addView(primaryText(card.body, 14f).apply {
            setPadding(dp(19), 0, dp(19), 0)
        })

        val stats = TwidgetStore.currentStats(this@TwidgetBriefActivity, username)
        addView(LinearLayout(this@TwidgetBriefActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(ImageView(this@TwidgetBriefActivity).apply {
                setImageDrawable(AppCompatResources.getDrawable(this@TwidgetBriefActivity, OneUiIconR.drawable.ic_oui_community))
                imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(primaryText(format(stats.followersCount), 22f, true).apply {
                setPadding(dp(20), 0, 0, 0)
            })
            val delta = TwidgetStore.followersDelta(this@TwidgetBriefActivity, username)
            if (delta != 0L) addView(primaryText(TwidgetStore.signedNumber(delta), 18f).apply {
                setPadding(dp(6), 0, 0, 0)
                setTextColor(getColor(if (delta < 0) R.color.metric_red else R.color.metric_green))
            })
        })
        val full = TwidgetStore.fullHistory(this@TwidgetBriefActivity, username).filter { it.followersKnown }
        val visible = TwidgetStore.chartHistory(this@TwidgetBriefActivity, username, HistoryRange.WEEK)
            .filter { it.followersKnown }
        addView(MetricChartView(this@TwidgetBriefActivity).apply {
            setData(visible, { it.followers })
            setAverageSeries(AccountAverageSeries.values(full, visible, { it.followers }))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(194)))
    }

    private fun postCard(post: PostSummary): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundResource(R.drawable.brief_card_background)
        isClickable = post.url.isNotBlank()
        isFocusable = isClickable
        if (isClickable) setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(post.url)))
        }

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(ImageView(context).apply {
                ProfileImageLoader.loadInto(context, this, post.authorAvatar)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@TwidgetBriefActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(primaryText(post.authorName.ifBlank { TwidgetStore.currentStats(context, username).fullName }, 14f, true))
                addView(supportingText("@${post.authorUserName.ifBlank { username }} · ${postDate(post)}", 12f))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        addView(primaryText(post.text.ifBlank { post.url }, 14f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWrap(top = 10))

        post.media.firstOrNull()?.let { media ->
            addView(ImageView(context).apply {
                contentDescription = media.alt.ifBlank { getString(R.string.post_media) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                ProfileImageLoader.loadMediaInto(context, this, media.url, dp(14))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(218),
            ).apply { topMargin = dp(10) })
        }

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addMetric(this, OneUiIconR.drawable.ic_oui_equalizer_2, post.views)
            addMetric(this, OneUiIconR.drawable.ic_oui_message_outline, post.replies)
            addMetric(this, OneUiIconR.drawable.ic_oui_heart_outline, post.likes)
            addMetric(this, OneUiIconR.drawable.ic_oui_repeat, post.reposts)
        }, matchWrap(top = 10))
    }

    private fun addMetric(row: LinearLayout, iconRes: Int, value: Long) {
        row.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            addView(ImageView(context).apply {
                setImageDrawable(AppCompatResources.getDrawable(context, iconRes))
                imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(primaryText(TwidgetStore.compactNumber(value), 14f).apply {
                setPadding(dp(4), 0, 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun topFollowersCard(card: BriefCard): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.brief_card_background)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            startRightSidePopOverActivity(
                Intent(this@TwidgetBriefActivity, TopFollowersBrowseActivity::class.java)
                    .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, username),
            )
        }
        addView(primaryText(card.body, 14f).apply {
            setPadding(dp(20), dp(16), dp(20), dp(10))
        })
        val followers = TopFollowersStore.read(this@TwidgetBriefActivity, username).top.take(3)
        if (followers.isEmpty()) {
            addView(supportingText(getString(R.string.brief_top_followers_empty), 14f).apply {
                setPadding(dp(20), dp(12), dp(20), dp(18))
            })
            return@apply
        }
        followers.forEachIndexed { index, follower ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
                minimumHeight = dp(68)
                addView(TextView(context).apply {
                    text = "${index + 1}"
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(getColor(R.color.oneui_text_primary))
                    textSize = 30f
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(dp(24), dp(40)))
                addView(ImageView(context).apply {
                    ProfileImageLoader.loadInto(context, this, follower.avatarUrl)
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, 0, 0)
                    addView(primaryText(follower.name.ifBlank { "@${follower.username}" }, 20f, true).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(supportingText("@${follower.username}", 14f))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(context).apply {
                    setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_community))
                    imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
                }, LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(supportingText(TwidgetStore.compactNumber(follower.followers), 14f).apply {
                    setPadding(dp(4), 0, 0, 0)
                })
            })
            if (index < followers.lastIndex) addView(View(this@TwidgetBriefActivity).apply {
                setBackgroundColor(getColor(R.color.brief_divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
        }
    }

    private fun render(snapshot: BriefSnapshot) {
        renderedSnapshot = snapshot
        val summary = BriefEditorialSummary.from(snapshot.cards)
        findViewById<TextView>(R.id.brief_summary_title).text = summary.title
        findViewById<TextView>(R.id.brief_summary_body).text = summary.body
        val columns = configureResponsiveLayout()
        val container = findViewById<LinearLayout>(R.id.brief_cards)
        container.removeAllViews()
        val sections = buildList {
            if (snapshot.upcomingTweets.isNotEmpty()) {
                add(upcomingSection(snapshot.upcomingTweets) to 24)
            }
            snapshot.cards.forEach { card -> add(cardSection(card) to 20) }
        }
        if (columns == 1) {
            container.orientation = LinearLayout.VERTICAL
            sections.forEach { (section, topMargin) ->
                container.addView(section, matchWrap(top = topMargin))
            }
        } else {
            container.orientation = LinearLayout.HORIZONTAL
            val masonryColumns = List(columns) { columnIndex ->
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    container.addView(
                        this,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (columnIndex == 0) rightMargin = dp(10) else leftMargin = dp(10)
                        },
                    )
                }
            }
            sections.forEachIndexed { index, (section, topMargin) ->
                masonryColumns[index % columns].addView(section, matchWrap(top = topMargin))
            }
        }
    }

    // Scheduled copy is rendered locally and never enters an AI prompt.
    private fun upcomingSection(tweets: List<BriefUpcomingTweet>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionLabel("Upcoming scheduled tweets"))
        tweets.forEachIndexed { index, tweet ->
            addView(scheduledPostCard(tweet), matchWrap(top = if (index == 0) 10 else 20))
        }
    }

    private fun scheduledPostCard(tweet: BriefUpcomingTweet): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.brief_card_background)
        setOnClickListener { openSchedule(tweet) }

        val stats = TwidgetStore.currentStats(this@TwidgetBriefActivity, username)
        val scheduledPost = ScheduleStore(this@TwidgetBriefActivity).get(tweet.id)
        val firstPost = scheduledPost?.thread?.firstOrNull()

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(ImageView(context).apply {
                ProfileImageLoader.loadInto(context, this, stats.profileImage)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@TwidgetBriefActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(primaryText(stats.fullName.ifBlank { "@$username" }, 14f, true))
                addView(supportingText("@$username · ${scheduleDate(tweet.scheduledAt)}", 12f))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        addView(primaryText(firstPost?.text.orEmpty().ifBlank {
            tweet.preview.ifBlank { "Scheduled tweet" }
        }, 14f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWrap(top = 10))

        firstPost?.media?.firstOrNull()?.let { media ->
            addView(ImageView(context).apply {
                contentDescription = getString(R.string.post_media)
                scaleType = ImageView.ScaleType.CENTER_CROP
                when (media) {
                    is LocalUriMedia -> {
                        background = AppCompatResources.getDrawable(context, R.drawable.schedule_media_preview_bg)
                        outlineProvider = ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                        runCatching { setImageURI(Uri.parse(media.uri)) }
                    }
                    is PublicUrlMedia -> ProfileImageLoader.loadMediaInto(
                        context,
                        this,
                        media.displayUrl,
                        dp(14),
                    )
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(218),
            ).apply { topMargin = dp(10) })
        }

        val details = buildList {
            if (tweet.threadCount > 1) add("${tweet.threadCount}-tweet thread")
            if (tweet.mediaCount > 0) add("${tweet.mediaCount} media")
            add(if (tweet.provider == ScheduleProvider.BUFFER) "Buffer" else "Local reminder")
        }.joinToString(" · ")
        addView(supportingText(details, 12f), matchWrap(top = 10))
    }

    private fun scheduleDate(timestamp: Long): String = if (timestamp > 0L) {
        SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(timestamp))
    } else {
        "Time unavailable"
    }

    private fun openSchedule(tweet: BriefUpcomingTweet) {
        reloadOnResume = true
        startRightSidePopOverActivity(
            Intent(this, ScheduleComposeActivity::class.java)
                .putExtra(ScheduleComposeActivity.EXTRA_USERNAME, username)
                .putExtra(ScheduleComposeActivity.EXTRA_SCHEDULE_ID, tweet.id),
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderedSnapshot?.let(::render) ?: configureResponsiveLayout()
    }

    override fun allowsPopOverPresentation(): Boolean = false

    private fun configureResponsiveLayout(): Int {
        val columns = BriefLayoutPolicy.columnCount(resources.configuration.screenWidthDp)
        findViewById<LinearLayout>(R.id.brief_content).layoutParams =
            (findViewById<LinearLayout>(R.id.brief_content).layoutParams as FrameLayout.LayoutParams).apply {
                width = if (columns == 1) {
                    FrameLayout.LayoutParams.MATCH_PARENT
                } else {
                    minOf(
                        resources.displayMetrics.widthPixels - dp(20),
                        dp(BriefLayoutPolicy.MAX_CONTENT_WIDTH_DP),
                    )
                }
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        findViewById<LinearLayout>(R.id.brief_footer).layoutParams =
            (findViewById<LinearLayout>(R.id.brief_footer).layoutParams as LinearLayout.LayoutParams).apply {
                width = if (columns == 1) {
                    LinearLayout.LayoutParams.MATCH_PARENT
                } else {
                    dp(488)
                }
                gravity = Gravity.CENTER_HORIZONTAL
            }
        return columns
    }

    private fun cardSection(card: BriefCard): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val analytics = AnalyticsClient.cached(context, username)
        val hasFetchedPost = when (card.type) {
            BriefCardType.POST -> analytics?.best != null
            BriefCardType.WORST_POST -> analytics?.worst != null
            else -> false
        }
        if (hasFetchedPost) {
            addView(sectionLabel(card.body).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        } else {
            addView(sectionLabel(sectionHeading(card)))
        }
        val content = when (card.type) {
            BriefCardType.MILESTONE -> milestoneCard(card)
            BriefCardType.STREAK -> StreakCardFactory.create(
                this@TwidgetBriefActivity,
                DailyStreakStore.snapshot(this@TwidgetBriefActivity, username),
                titleOverride = card.title,
                detailOverride = card.body,
            )
            BriefCardType.GROWTH, BriefCardType.SLOWDOWN -> followerChartCard(card)
            BriefCardType.POST -> analytics?.best?.let { postCard(it) }
                ?: genericCard(card)
            BriefCardType.WORST_POST -> analytics?.worst?.let { postCard(it) }
                ?: genericCard(card)
            BriefCardType.TOP_FOLLOWER -> topFollowersCard(card)
            BriefCardType.INACTIVITY -> genericCard(card, warm = true)
            else -> genericCard(card)
        }
        addView(content, matchWrap(top = 10))
    }

    private fun sectionHeading(card: BriefCard): String = when (card.type) {
        BriefCardType.MILESTONE -> {
            val settings = MilestoneGoalStore.read(this, username)
            val stats = TwidgetStore.currentStats(this, username)
            val metric = MilestoneMetricResolver.resolve(
                context = this,
                account = username,
                metric = settings.metric,
                stats = stats,
                history = TwidgetStore.fullHistory(this, username),
                analytics = AnalyticsClient.cached(this, username),
                imported = ImportedAnalyticsStore.all(this, username),
            )
            val progress = metric.value?.let { MilestonePolicy.progress(it, settings.target) } ?: 0
            when {
                progress >= 100 -> getString(R.string.brief_goal_reached_heading)
                progress >= 75 -> getString(R.string.brief_goal_close_heading)
                else -> getString(R.string.brief_goal_heading)
            }
        }
        BriefCardType.STREAK -> getString(R.string.brief_streak_heading)
        else -> card.title
    }

    private fun genericCard(card: BriefCard, warm: Boolean = false): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(19), dp(16), dp(19), dp(16))
        background = if (warm) radialCard(
            Color.rgb(255, 189, 157),
            getColor(R.color.oneui_card_bg),
        ) else AppCompatResources.getDrawable(context, R.drawable.brief_card_background)
        addView(primaryText(card.body, 14f))
    }

    private fun milestoneCard(card: BriefCard): View {
        val root = LayoutInflater.from(this).inflate(R.layout.milestone_card, null, false)
        val settings = MilestoneGoalStore.read(this, username)
        val stats = TwidgetStore.currentStats(this, username)
        val metric = MilestoneMetricResolver.resolve(
            context = this,
            account = username,
            metric = settings.metric,
            stats = stats,
            history = TwidgetStore.fullHistory(this, username),
            analytics = AnalyticsClient.cached(this, username),
            imported = ImportedAnalyticsStore.all(this, username),
        )
        val progress = MilestonePolicy.progress(metric.value, settings.target) ?: 0
        val state = MilestonePolicy.performanceState(metric.history)
        val accent = when (state) {
            MilestonePerformanceState.ACCELERATING -> Color.rgb(15, 207, 110)
            MilestonePerformanceState.DECELERATING -> Color.rgb(255, 103, 31)
            MilestonePerformanceState.NEUTRAL -> Color.rgb(24, 129, 255)
        }
        val glow = when (state) {
            MilestonePerformanceState.ACCELERATING -> Color.rgb(173, 255, 213)
            MilestonePerformanceState.DECELERATING -> Color.rgb(255, 196, 168)
            MilestonePerformanceState.NEUTRAL -> Color.rgb(141, 204, 255)
        }
        root.background = MilestoneCardBackgroundDrawable(
            glowColor = if (isNight()) darken(glow) else glow,
            surfaceColor = getColor(R.color.oneui_card_bg),
            radiusPx = dp(28).toFloat(),
        )
        root.findViewById<MilestoneArcView>(R.id.milestone_arc).apply {
            this.progress = progress
            progressColor = accent
        }
        root.findViewById<TextView>(R.id.milestone_title).text = card.title
        root.findViewById<TextView>(R.id.milestone_message).text = card.body
        val openEditor = View.OnClickListener {
            reloadOnResume = true
            startRightSidePopOverActivity(MilestoneGoalActivity.intent(this, username))
        }
        root.setOnClickListener(openEditor)
        root.findViewById<ImageButton>(R.id.milestone_edit).setOnClickListener(openEditor)
        root.contentDescription = "${card.title}. ${card.body}"
        return root
    }

    private fun radialCard(start: Int, end: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(320).toFloat()
        setGradientCenter(0f, .5f)
        cornerRadius = dp(28).toFloat()
        colors = intArrayOf(start, end)
    }

    private fun isNight(): Boolean = resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun darken(color: Int): Int = Color.rgb(
        (Color.red(color) * .3f).toInt(),
        (Color.green(color) * .3f).toInt(),
        (Color.blue(color) * .3f).toInt(),
    )

    private fun showProviderInfo() {
        val snapshot = renderedSnapshot ?: return
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.brief_ai_disclaimer)
            .setMessage(getString(R.string.brief_ai_disclaimer_body, snapshot.providerMessage))
            .setNegativeButton(android.R.string.cancel, null)
        if (localStatus == BriefLocalStatus.DOWNLOADABLE) {
            builder.setPositiveButton(R.string.brief_prepare_local) { _, _ -> downloadLocalModel() }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun downloadLocalModel() {
        lifecycleScope.launch {
            setLoading(true)
            try {
                localStatus = BriefLocalStatus.DOWNLOADING
                val downloaded = BriefAiCoordinator.downloadLocalModel { }
                if (!downloaded) {
                    localStatus = BriefLocalStatus.DOWNLOADABLE
                    return@launch
                }
                val source = BriefStore.read(this@TwidgetBriefActivity, username) ?: return@launch
                val result = BriefAiCoordinator.enrich(this@TwidgetBriefActivity, source, force = true)
                localStatus = result.localStatus
                render(result.snapshot)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun sectionLabel(value: String) = primaryText(value, 14f).apply {
        setPadding(dp(19), 0, dp(19), 0)
    }

    private fun primaryText(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        includeFontPadding = false
        setTextColor(getColor(R.color.oneui_text_primary))
        textSize = size
        typeface = Typeface.create("sec", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun supportingText(value: String, size: Float) = TextView(this).apply {
        text = value
        includeFontPadding = false
        setTextColor(getColor(R.color.brief_text_supporting))
        textSize = size
        typeface = Typeface.create("sec", Typeface.NORMAL)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun postDate(post: PostSummary): String = if (post.timestamp > 0L) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(post.timestamp))
    } else post.createdAt

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    companion object {
        const val EXTRA_USERNAME = "username"
        private const val EXTRA_DEBUG_SCENARIO = "brief_debug_scenario"

        fun intent(context: Context, username: String) =
            Intent(context, TwidgetBriefActivity::class.java).putExtra(EXTRA_USERNAME, username)

        fun debugIntent(context: Context, username: String, scenario: BriefDebugScenario) =
            intent(context, username).putExtra(EXTRA_DEBUG_SCENARIO, scenario.storageId)
    }
}
