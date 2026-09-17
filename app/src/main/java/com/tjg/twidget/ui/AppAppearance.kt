package com.tjg.twidget.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.tjg.twidget.data.TwidgetStore

/** App colours and typography are independent of widget appearance. */
object AppAppearance {
    private const val KEY = "app_night_mode"
    private const val FONT_KEY = "app_font_family"

    enum class Font(val value: String) {
        DEFAULT("default"), GOOGLE_SANS_FLEX("google_sans_flex"), SYSTEM("system")
    }

    fun font(context: Context): Font {
        val value = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
            .getString(FONT_KEY, null)
        return Font.entries.firstOrNull { it.value == value } ?: Font.DEFAULT
    }

    fun setFont(context: Context, font: Font) {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
            .edit().putString(FONT_KEY, font.value).apply()
    }

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
