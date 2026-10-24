package com.tjg.twidget.brief

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.tjg.twidget.R

/** Figma 372:11735: a regular Brief card that opens the complete review library. */
internal object BriefReviewLibraryCard {
    fun create(context: Context, openLibrary: () -> Unit): View = BriefReviewViews.column(context).apply {
        addView(BriefReviewViews.label(context, context.getString(R.string.review_library_intro)).apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        })
        addView(BriefReviewViews.card(context).apply {
            id = R.id.review_library_card
            isClickable = true
            isFocusable = true
            clipToOutline = true
            contentDescription = context.getString(R.string.review_library_title) + ". " +
                context.getString(R.string.review_library_body)
            val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            foreground = attrs.getDrawable(0)
            attrs.recycle()
            setOnClickListener { openLibrary() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.brief_review_library_artwork)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(BriefReviewViews.dp(context, 89), BriefReviewViews.dp(context, 80)))
                addView(BriefReviewViews.column(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(BriefReviewViews.text(context, context.getString(R.string.review_library_title), 14f, bold = true).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(BriefReviewViews.text(context, context.getString(R.string.review_library_body), 12f).apply {
                        setTextColor(ColorUtils.setAlphaComponent(context.getColor(R.color.oneui_text_primary), 153))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, BriefReviewViews.params(context, 6))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = BriefReviewViews.dp(context, 20)
                })
            })
        }, BriefReviewViews.params(context, 10))
    }
}
