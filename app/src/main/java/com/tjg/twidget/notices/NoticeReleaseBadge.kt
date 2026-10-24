package com.tjg.twidget.notices

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.update.ReleaseNotice

internal object NoticeReleaseBadge {
    fun bind(view: TextView, notice: ReleaseNotice) = with(view) {
        visibility = if (notice.prerelease || notice.upcoming) View.VISIBLE else View.GONE
        setText(if (notice.upcoming) R.string.notices_upcoming else R.string.notices_beta)
        textSize = 14f
        typeface = TwidgetFonts.forApp(context, 700)
        setTextColor(Color.WHITE)
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        includeFontPadding = false
        gravity = Gravity.CENTER
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(30).toFloat()
            setColor(context.getColor(R.color.notice_badge_orange))
        }
    }
}
