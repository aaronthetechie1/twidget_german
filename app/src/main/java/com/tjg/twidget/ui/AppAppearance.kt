package com.tjg.twidget.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.tjg.twidget.data.TwidgetStore

/** App theme is independent of each widget's colour mode. */
object AppAppearance {
    private const val KEY = "app_night_mode"
    fun mode(context: Context): Int = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        .getInt(KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        .takeIf { it in setOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES) }
        ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun apply(context: Context) = AppCompatDelegate.setDefaultNightMode(mode(context))
}
