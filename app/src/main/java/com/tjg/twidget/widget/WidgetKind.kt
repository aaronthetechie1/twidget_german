package com.tjg.twidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context

/** Defaults are shared within a widget type; configured instances keep their overrides. */
enum class WidgetKind(val defaultsSuffix: String) {
    FOLLOWERS(""), BRIEF("_brief"), LOCK_SCREEN("_lock_screen");

    companion object {
        fun forWidget(context: Context, id: Int): WidgetKind {
            if (id <= 0) return FOLLOWERS
            return fromProvider(AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className)
        }

        internal fun fromProvider(name: String?): WidgetKind = when {
            name == TwidgetBriefWidget::class.java.name -> BRIEF
            name?.contains("LockScreenFollower") == true -> LOCK_SCREEN
            else -> FOLLOWERS
        }
    }
}
