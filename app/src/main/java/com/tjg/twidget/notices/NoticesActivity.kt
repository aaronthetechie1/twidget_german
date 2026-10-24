package com.tjg.twidget.notices

import android.content.Intent
import android.util.TypedValue
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.startRightSidePopOverActivity
import com.tjg.twidget.update.AppUpdateManager
import com.tjg.twidget.update.ReleaseNotice
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.widget.RoundedLinearLayout
import dev.oneuiproject.oneui.widget.Separator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class NoticesActivity : FoldablePopOverActivity() {
    private lateinit var content: LinearLayout
    private lateinit var refresh: SwipeRefreshLayout
    private var generation = 0
    private var notices = emptyList<ReleaseNotice>()
    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notices)
        content = findViewById(R.id.notices_content)
        refresh = findViewById<SwipeRefreshLayout>(R.id.notices_refresh).apply {
            setOnRefreshListener { refreshNotices() }
        }
        findViewById<ToolbarLayout>(R.id.notices_root)
            .setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyEdgeToEdgeInsets(findViewById(R.id.notices_root))

        notices = ReleaseNoticesStore.visible(this)
        ReleaseNoticesStore.markCurrentAsSeen(this)
        render()
        refreshNotices()
    }

    override fun onDestroy() {
        generation++
        super.onDestroy()
    }

    private fun refreshNotices() {
        val token = ++generation
        refresh.isRefreshing = true
        errorMessage = null
        AppExecutors.execute(onRejected = {
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                refresh.isRefreshing = false
                errorMessage = getString(R.string.notices_load_failed)
                render()
            }
        }) {
            val result = runCatching { AppUpdateManager.fetchReleaseNotices() }
            result.getOrNull()?.let {
                ReleaseNoticesStore.save(applicationContext, it)
                ReleaseNoticesStore.markCurrentAsSeen(applicationContext)
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                refresh.isRefreshing = false
                result.onSuccess {
                    notices = ReleaseNoticesStore.visible(this)
                }.onFailure {
                    errorMessage = getString(R.string.notices_load_failed)
                }
                render()
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        errorMessage?.let {
            content.addView(card().apply {
                addView(titleText(it))
                if (notices.any { !it.upcoming }) addView(metaText(getString(R.string.notices_showing_cache)))
            })
        }
        if (notices.isEmpty()) {
            if (!refresh.isRefreshing) content.addView(emptyState())
        } else {
            notices.filter { it.upcoming }.takeIf { it.isNotEmpty() }?.let { upcoming ->
                content.addView(section(R.string.notices_upcoming))
                upcoming.forEach { content.addView(noticeCard(it)) }
            }
            val published = notices.filterNot { it.upcoming }
            val latest = published.firstOrNull { !it.prerelease } ?: published.firstOrNull()
            latest?.let {
                content.addView(section(R.string.notices_latest_version))
                content.addView(noticeCard(it))
            }
            published.filter { it !== latest }.takeIf { it.isNotEmpty() }?.let { older ->
                content.addView(section(R.string.notices_older_releases))
                older.forEach { content.addView(noticeCard(it)) }
            }
        }
        TwidgetFonts.applyTo(content)
    }

    private fun noticeCard(notice: ReleaseNotice): View = card().apply {
        tag = notice.tag
        val feedback = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, feedback, true)
        foreground = AppCompatResources.getDrawable(context, feedback.resourceId)
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.notices_open_changelog, notice.title)
        setOnClickListener {
            startRightSidePopOverActivity(
                Intent(this@NoticesActivity, NoticeDetailActivity::class.java)
                    .putExtra(NoticeDetailActivity.EXTRA_NOTICE_TAG, notice.tag)
            )
        }

        addView(LinearLayout(this@NoticesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@NoticesActivity).apply {
                text = notice.title.removePrefix("Twidget ")
                textSize = 18f
                includeFontPadding = false
                setTextColor(getColor(R.color.oneui_text_primary))
                TwidgetFonts.setRole(this, TwidgetFonts.Role.CODE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (notice.prerelease || notice.upcoming) {
                addView(TextView(this@NoticesActivity).apply {
                    NoticeReleaseBadge.bind(this, notice)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }
        })
        addView(metaText(releaseMeta(notice)))
        val body = ReleaseNoticeText.summary(notice.body)
        addView(TextView(this@NoticesActivity).apply {
            text = body.ifBlank { getString(R.string.notices_no_details) }
            textSize = 14f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(getColor(R.color.oneui_text_secondary))
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun releaseMeta(notice: ReleaseNotice): String {
        if (notice.upcoming) return getString(R.string.notices_upcoming_summary)
        val date = runCatching {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .format(Instant.parse(notice.publishedAt).atZone(ZoneId.systemDefault()))
        }.getOrNull()
        return date.orEmpty()
    }

    private fun emptyState(): View = card().apply {
        gravity = Gravity.CENTER
        addView(titleText(getString(R.string.notices_empty_title)).apply { gravity = Gravity.CENTER })
        addView(metaText(getString(R.string.notices_empty_summary)).apply { gravity = Gravity.CENTER })
        addView(AppCompatButton(this@NoticesActivity).apply {
            setText(R.string.notices_retry)
            isAllCaps = false
            setOnClickListener { refreshNotices() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            topMargin = dp(12)
        })
    }

    private fun section(label: Int): View = Separator(this).apply {
        setText(label)
    }

    private fun card(): LinearLayout = RoundedLinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        roundedCorners = androidx.appcompat.util.SeslRoundedCorner.ROUNDED_CORNER_ALL
        roundedCornersColor = getColor(R.color.oneui_bg)
        setBackgroundColor(getColor(R.color.oneui_card_bg))
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun titleText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        typeface = TwidgetFonts.forApp(context, 700)
        TwidgetFonts.setRole(this, TwidgetFonts.Role.LABEL)
        setTextColor(getColor(R.color.oneui_text_primary))
    }

    private fun metaText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        includeFontPadding = false
        TwidgetFonts.setRole(this, TwidgetFonts.Role.SUMMARY)
        setTextColor(getColor(R.color.oneui_text_secondary))
        setPadding(0, dp(8), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
