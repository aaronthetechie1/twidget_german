package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.AccountAverageSeries
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.MetricChartView
import com.tjg.twidget.ui.ProfileImageLoader
import dev.oneuiproject.oneui.R as OneUiIconR
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class TwidgetBriefActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private var renderedSnapshot: BriefSnapshot? = null
    private var localStatus = BriefLocalStatus.UNAVAILABLE

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
        BriefSettingsStore.setEnabled(this, true)

        bindChrome()
        bindFollowerChart()
        bindPost()
        bindTopFollowers()

        val source = BriefEngine.rebuild(this, username)
        render(source)
        lifecycleScope.launch {
            val result = BriefAiCoordinator.enrich(this@TwidgetBriefActivity, source)
            render(result.snapshot)
            localStatus = result.localStatus
        }
    }

    private fun bindChrome() {
        applyEdgeToEdgeInsets(findViewById(R.id.brief_root))
        val tint = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
        findViewById<ImageButton>(R.id.brief_back).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_back))
            imageTintList = tint
            setOnClickListener { finish() }
        }
        findViewById<ImageButton>(R.id.brief_info).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_info_outline))
            imageTintList = tint
            setOnClickListener { showProviderInfo() }
        }
        findViewById<ImageView>(R.id.brief_followers_icon).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_community))
            imageTintList = tint
        }
        findViewById<ImageView>(R.id.brief_refresh_icon).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_refresh))
            imageTintList = tint
        }
    }

    private fun bindFollowerChart() {
        val stats = TwidgetStore.currentStats(this, username)
        findViewById<TextView>(R.id.brief_followers_value).text = format(stats.followersCount)
        findViewById<TextView>(R.id.brief_followers_delta).apply {
            val delta = TwidgetStore.followersDelta(this@TwidgetBriefActivity, username)
            text = if (delta == 0L) "" else TwidgetStore.signedNumber(delta)
            visibility = if (delta == 0L) View.GONE else View.VISIBLE
            setTextColor(getColor(if (delta < 0) R.color.metric_red else R.color.metric_green))
        }
        val full = TwidgetStore.fullHistory(this, username).filter { it.followersKnown }
        val visible = TwidgetStore.chartHistory(this, username, HistoryRange.WEEK)
            .filter { it.followersKnown }
        findViewById<MetricChartView>(R.id.brief_chart).apply {
            setData(visible, { it.followers })
            setAverageSeries(AccountAverageSeries.values(full, visible, { it.followers }))
        }
    }

    private fun bindPost() {
        val post = AnalyticsClient.cached(this, username)?.best ?: return
        val section = findViewById<LinearLayout>(R.id.brief_post_section)
        section.visibility = View.VISIBLE
        section.removeAllViews()
        section.addView(sectionLabel(getString(R.string.brief_post_love)))
        section.addView(postCard(post), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
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
            addView(LinearLayout(context).apply {
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

    private fun bindTopFollowers() {
        val container = findViewById<LinearLayout>(R.id.brief_top_followers)
        container.removeAllViews()
        val followers = TopFollowersStore.read(this, username).top.take(3)
        if (followers.isEmpty()) {
            container.addView(supportingText(getString(R.string.brief_top_followers_empty), 14f).apply {
                setPadding(dp(20), dp(12), dp(20), dp(18))
            })
            return
        }
        followers.forEachIndexed { index, follower ->
            container.addView(LinearLayout(this).apply {
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
            if (index < followers.lastIndex) container.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.brief_divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
        }
    }

    private fun render(snapshot: BriefSnapshot) {
        renderedSnapshot = snapshot
        val hero = snapshot.cards.first()
        findViewById<TextView>(R.id.brief_hero_title).text = hero.title
        findViewById<TextView>(R.id.brief_hero_body).text = hero.body
        findViewById<TextView>(R.id.brief_growth_label).text = when {
            snapshot.followersWeek > 0 -> getString(R.string.brief_weekly_growth, format(snapshot.followersWeek))
            snapshot.followersWeek < 0 -> getString(R.string.brief_weekly_decline, format(-snapshot.followersWeek))
            else -> getString(R.string.brief_weekly_steady)
        }
        findViewById<TextView>(R.id.brief_followers_label).text = if (
            snapshot.cards.any { it.type == BriefCardType.TOP_FOLLOWER }
        ) getString(R.string.brief_new_top_follower) else getString(R.string.brief_top_followers_now)

        val container = findViewById<LinearLayout>(R.id.brief_cards)
        container.removeAllViews()
        snapshot.cards.drop(1)
            .filterNot {
                it.type == BriefCardType.GROWTH ||
                    it.type == BriefCardType.POST ||
                    it.type == BriefCardType.TOP_FOLLOWER
            }
            .forEach { card ->
                container.addView(sectionLabel(card.title), matchWrap(top = 20))
                container.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(19), dp(16), dp(19), dp(16))
                    setBackgroundResource(R.drawable.brief_card_background)
                    addView(primaryText(card.body, 14f))
                }, matchWrap(top = 10))
            }
    }

    private fun showProviderInfo() {
        val snapshot = renderedSnapshot ?: return
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.brief_title)
            .setMessage(snapshot.providerMessage)
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

        fun intent(context: Context, username: String) =
            Intent(context, TwidgetBriefActivity::class.java).putExtra(EXTRA_USERNAME, username)
    }
}
