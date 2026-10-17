package com.tjg.twidget.brief

import android.view.View
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout
import com.tjg.twidget.R

/** Native floating controls sit above the Brief's own transparent scrolling fade. */
internal object BriefPageChrome {
    fun compact(root: View, onBack: () -> Unit) {
        root.findViewById<Toolbar>(R.id.brief_back).apply {
            title = null
            setNavigationIcon(dev.oneuiproject.oneui.R.drawable.ic_oui_back)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { onBack() }
        }
        listOf<View>(root.findViewById(R.id.brief_app_bar), root.findViewById(R.id.brief_collapsing_toolbar),
            root.findViewById(R.id.brief_back)).forEach {
            it.setBackgroundColor(Color.TRANSPARENT)
            it.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        val appBar = root.findViewById<AppBarLayout>(R.id.brief_app_bar)
        // The same native fixed-height configuration used by ToolbarLayout(expandable=false).
        appBar.isEnabled = false
        appBar.seslSetCustomHeight(root.resources.getDimensionPixelSize(androidx.appcompat.R.dimen.sesl_action_bar_height_with_padding) + appBar.paddingTop)
        val fade = root.findViewById<View>(R.id.brief_top_gradient)
        appBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val height = BriefReviewViews.dp(root.context, 96) + appBar.paddingTop
            if (fade.layoutParams.height != height) fade.layoutParams = fade.layoutParams.apply { this.height = height }
        }
    }

    fun navigationInset(scroll: NestedScrollView, bottom: Int) {
        scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, bottom)
        scroll.clipToPadding = false
    }

    fun review(root: View, title: Int, onBack: () -> Unit): Toolbar {
        val toolbar = root.findViewById<Toolbar>(R.id.review_toolbar).apply {
            setTitle(title)
            setNavigationIcon(dev.oneuiproject.oneui.R.drawable.ic_oui_back)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { onBack() }
        }
        root.findViewById<CollapsingToolbarLayout>(R.id.review_collapsing_toolbar).title = root.context.getString(title)
        val appBar = root.findViewById<AppBarLayout>(R.id.review_app_bar)
        // SESL applies a surface tint as the app bar collapses unless it is cleared too.
        listOf<View>(appBar, toolbar, root.findViewById(R.id.review_collapsing_toolbar)).forEach {
            it.setBackgroundColor(Color.TRANSPARENT)
            it.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        val scroll = root.findViewById<NestedScrollView>(R.id.review_scroll)
        (toolbar.parent as? FloatingToolbarLayout)?.setNestedScrollView(scroll)
        val fade = root.findViewById<View>(R.id.review_top_gradient)
        fun updateFade(offset: Int) {
            val overflow = BriefReviewViews.dp(root.context, 96)
            val height = overflow + appBar.paddingTop
            if (fade.layoutParams.height != height) fade.layoutParams = fade.layoutParams.apply { this.height = height }
            val range = appBar.height - appBar.seslGetCollapsedHeight()
            val expanded = if (range > 0) (1f + offset / range).coerceIn(0f, 1f) else 0f
            // Clear the expanded heading, then restore the overflow fade as it collapses.
            fade.translationY = -overflow * expanded
        }
        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { _, offset -> updateFade(offset) })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFade(appBar.top) }
        root.post { updateFade(appBar.top) }
        // SESL's opaque page fade would cover the custom Brief gradient.
        scroll.seslSetFadingEdgeEnabled(false)
        return toolbar
    }
}
