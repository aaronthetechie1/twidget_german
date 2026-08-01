package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.tjg.twidget.R
import com.tjg.twidget.data.AccountAverageSeries
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.MetricChartView
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.text.NumberFormat
import kotlinx.coroutines.launch

class TwidgetBriefActivity : FoldablePopOverActivity() {
    private lateinit var username: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_twidget_brief)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')
            .ifBlank { TwidgetStore.settings(this).username }
        if (username.isBlank()) {
            finish()
            return
        }
        BriefSettingsStore.setEnabled(this, true)

        val stats = TwidgetStore.currentStats(this, username)
        findViewById<ToolbarLayout>(R.id.brief_root).apply {
            setTitle(stats.fullName.ifBlank { "@$username" })
            setNavigationButtonOnClickListener { finish() }
            applyEdgeToEdgeInsets(this)
        }
        findViewById<TextView>(R.id.brief_sync).text = TwidgetStore.lastSyncedText(this, stats)
        bindFollowerChart()
        bindTopFollowers()

        val source = BriefEngine.rebuild(this, username)
        render(source)
        lifecycleScope.launch {
            val result = BriefAiCoordinator.enrich(this@TwidgetBriefActivity, source)
            render(result.snapshot)
            updateLocalAction(result.localStatus)
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

    private fun bindTopFollowers() {
        val container = findViewById<LinearLayout>(R.id.brief_top_followers)
        container.removeAllViews()
        val followers = TopFollowersStore.read(this, username).top.take(5)
        if (followers.isEmpty()) {
            container.addView(bodyText(getString(R.string.brief_top_followers_empty)))
            return
        }
        followers.forEachIndexed { index, follower ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                addView(TextView(context).apply {
                    text = "${index + 1}"
                    gravity = android.view.Gravity.CENTER
                    setTextColor(getColor(R.color.oneui_accent))
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = follower.name.ifBlank { "@${follower.username}" }
                        setTextColor(getColor(R.color.oneui_text_primary))
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(context).apply {
                        text = "@${follower.username}"
                        setTextColor(getColor(R.color.oneui_text_secondary))
                        textSize = 13f
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = format(follower.followers)
                    setTextColor(getColor(R.color.oneui_text_secondary))
                    textSize = 14f
                })
            })
        }
    }

    private fun render(snapshot: BriefSnapshot) {
        val hero = snapshot.cards.first()
        findViewById<TextView>(R.id.brief_hero_title).text = hero.title
        findViewById<TextView>(R.id.brief_hero_body).text = hero.body
        findViewById<TextView>(R.id.brief_provider_status).text = snapshot.providerMessage

        val container = findViewById<LinearLayout>(R.id.brief_cards)
        container.removeAllViews()
        snapshot.cards.drop(1).forEach { card -> container.addView(insightCard(card)) }
    }

    private fun insightCard(card: BriefCard): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(getColor(R.color.oneui_card_bg))
        }
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
        addView(TextView(context).apply {
            text = card.title
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(bodyText(card.body).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        })
    }

    private fun updateLocalAction(status: BriefLocalStatus) {
        findViewById<Button>(R.id.brief_prepare_local).apply {
            visibility = if (status == BriefLocalStatus.DOWNLOADABLE || status == BriefLocalStatus.DOWNLOADING) {
                View.VISIBLE
            } else View.GONE
            isEnabled = status == BriefLocalStatus.DOWNLOADABLE
            setOnClickListener {
                isEnabled = false
                lifecycleScope.launch {
                    val downloaded = BriefAiCoordinator.downloadLocalModel { message ->
                        runOnUiThread { findViewById<TextView>(R.id.brief_provider_status).text = message }
                    }
                    if (downloaded) {
                        val result = BriefAiCoordinator.enrich(
                            this@TwidgetBriefActivity,
                            requireNotNull(BriefStore.read(this@TwidgetBriefActivity, username)),
                            force = true,
                        )
                        render(result.snapshot)
                        updateLocalAction(result.localStatus)
                    } else {
                        isEnabled = true
                    }
                }
            }
        }
    }

    private fun bodyText(value: String) = TextView(this).apply {
        text = value
        setTextColor(getColor(R.color.oneui_text_secondary))
        textSize = 14f
        setLineSpacing(0f, 1.12f)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    companion object {
        const val EXTRA_USERNAME = "username"

        fun intent(context: Context, username: String) =
            Intent(context, TwidgetBriefActivity::class.java).putExtra(EXTRA_USERNAME, username)
    }
}
