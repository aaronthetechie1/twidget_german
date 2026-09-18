package com.tjg.twidget.notices

import android.widget.FrameLayout

internal object NoticeReaderChrome {
    fun updateInsets(host: FrameLayout, left: Int, top: Int, right: Int) {
        val offset = (18 * host.resources.displayMetrics.density).toInt()
        host.layoutParams = (host.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = left + offset
            marginEnd = right
            topMargin = top + offset
        }
    }

    fun install(host: FrameLayout, onBack: () -> Unit) {
        host.setOnClickListener { onBack() }
    }
}
