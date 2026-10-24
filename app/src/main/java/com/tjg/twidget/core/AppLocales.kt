package com.tjg.twidget.core

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.core.app.LocaleManagerCompat
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves the in-app language for UI, widgets, and number/date formatting.
 * Widget settings may override the app locale with an explicit `de` / `en` tag;
 * `DEFAULT` follows [AppCompatDelegate] when a language is stored, else the
 * system locale.
 */
object AppLocales {
    const val DEFAULT = "DEFAULT"

    private var appContext: Context? = null

    /** Restore AppCompat's persisted choice before a worker or widget is started. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AppCompatDelegate.setApplicationLocales(LocaleManagerCompat.getApplicationLocales(context))
        }
    }

    internal fun supportedLocale(locale: Locale): Locale = when (locale.language) {
        "de", "en", "es", "fr" -> locale
        else -> Locale.ENGLISH
    }

    fun resolve(languageTag: String? = DEFAULT): Locale = when (languageTag?.lowercase(Locale.ROOT)) {
        "de" -> Locale.GERMAN
        "en" -> Locale.ENGLISH
        "es" -> Locale.forLanguageTag("es")
        "fr" -> Locale.FRENCH
        else -> applicationLocale()
    }

    fun applicationLocale(): Locale {
        if (appContext == null) return supportedLocale(Locale.getDefault())
        val stored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && appContext != null) {
            LocaleManagerCompat.getApplicationLocales(requireNotNull(appContext))
        } else {
            AppCompatDelegate.getApplicationLocales()
        }
        return supportedLocale(stored.takeUnless { it.isEmpty }?.get(0)
            ?: Resources.getSystem().configuration.locales.get(0) ?: Locale.ENGLISH)
    }

    fun wrap(context: Context, languageTag: String? = DEFAULT): Context {
        val locale = resolve(languageTag)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    fun integer(value: Long, locale: Locale = applicationLocale()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    fun date(value: java.time.LocalDate, locale: Locale = applicationLocale()): String =
        value.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))

    fun dateTime(context: Context, timestamp: Long): String {
        val locale = supportedLocale(context.resources.configuration.locales[0])
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(Date(timestamp))
        val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(timestamp))
        return "$date · $time"
    }

    fun formatDate(
        timestamp: Long,
        germanPattern: String,
        englishPattern: String,
        frenchPattern: String,
        locale: Locale = applicationLocale(),
    ): String {
        val pattern = when (locale.language) {
            "de" -> germanPattern
            "fr" -> frenchPattern
            "es" -> englishPattern.replace("MMM d", "d MMM").replace("h:mm a", "HH:mm")
            else -> englishPattern
        }
        val context = appContext
        val actualPattern = if (context != null) {
            val skeleton = buildString {
                if ('E' in pattern) append("EEE")
                if ('y' in pattern) append("y")
                append(if ("MMM" in pattern) "MMM" else "M")
                append("d")
                if ('H' in pattern || 'h' in pattern) {
                    append(if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "hm")
                    if ('s' in pattern) append("s")
                }
            }
            android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        } else pattern
        return SimpleDateFormat(actualPattern, locale).format(Date(timestamp))
    }
}
