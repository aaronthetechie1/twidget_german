package com.tjg.twidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.brief.TwidgetBriefActivity
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetFonts

class TwidgetBriefWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        BriefSettingsStore.setEnabled(context, true)
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, TwidgetBriefWidget::class.java))
                .forEach { update(context, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val username = TwidgetStore.settings(context).username
            val options = manager.getAppWidgetOptions(id)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 352)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 76)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val width = if (!TwidgetFonts.hasSystemOneUiSans && landscape) {
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
            } else minWidth
            val height = if (!TwidgetFonts.hasSystemOneUiSans && !landscape) {
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
            } else minHeight
            val settings = TwidgetStore.widgetSettings(context, id)
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val snapshot = BriefStore.read(context, username)
                ?: if (username.isNotBlank() && BriefSettingsStore.enabled(context)) {
                    BriefEngine.rebuild(context, username)
                } else null
            val views = RemoteViews(context.packageName, R.layout.widget_twidget_brief).apply {
                val surface = if (dark) Color.argb(153, 16, 16, 16) else Color.argb(153, 255, 255, 255)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setColorStateList(
                        android.R.id.background,
                        "setBackgroundTintList",
                        ColorStateList.valueOf(surface),
                    )
                }
                setImageViewBitmap(
                    R.id.brief_widget_artwork,
                    BriefWidgetArtworkRenderer.render(
                        context = context,
                        widthPx = dp(context, width),
                        heightPx = dp(context, height),
                        settings = settings,
                        account = username,
                        snapshot = snapshot,
                        dark = dark,
                        drawBackground = !TwidgetFonts.hasSystemOneUiSans,
                    ),
                )
                if (username.isNotBlank()) {
                    setOnClickPendingIntent(
                        android.R.id.background,
                        PendingIntent.getActivity(
                            context,
                            9321 + id,
                            TwidgetBriefActivity.intent(context, username),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
            manager.updateAppWidget(id, views)
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
