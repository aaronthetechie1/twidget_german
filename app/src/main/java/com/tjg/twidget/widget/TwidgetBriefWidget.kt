package com.tjg.twidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.brief.TwidgetBriefActivity
import com.tjg.twidget.data.TwidgetStore

class TwidgetBriefWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TwidgetBriefWidget::class.java)
            manager.getAppWidgetIds(component).forEach { manager.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val username = TwidgetStore.settings(context).username
            val snapshot = BriefStore.read(context, username)
                ?: if (username.isNotBlank() && BriefSettingsStore.enabled(context)) {
                    BriefEngine.rebuild(context, username)
                } else null
            val card = snapshot?.cards?.firstOrNull()
            return RemoteViews(context.packageName, R.layout.widget_twidget_brief).apply {
                setTextViewText(R.id.brief_widget_title, card?.title ?: context.getString(R.string.brief_widget_empty_title))
                setTextViewText(R.id.brief_widget_body, card?.body ?: context.getString(R.string.brief_widget_empty_body))
                if (username.isNotBlank()) {
                    val intent = TwidgetBriefActivity.intent(context, username)
                    setOnClickPendingIntent(
                        R.id.brief_widget_root,
                        PendingIntent.getActivity(
                            context,
                            9321,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
        }
    }
}
