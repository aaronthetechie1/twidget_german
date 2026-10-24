package com.tjg.twidget.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.data.TwidgetStore
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class RegionalFormattingInstrumentedTest {
    private fun context(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tag))
        })
    }

    @Test fun separatorsAndCompactUnitsFollowTheRegionAndLanguage() {
        val spain = Locale.forLanguageTag("es-ES")
        val mexico = Locale.forLanguageTag("es-MX")
        assertEquals("12.345", AppLocales.integer(12345, spain))
        assertEquals("12,345", AppLocales.integer(12345, mexico))
        assertTrue(TwidgetStore.compactNumber(12500, spain).contains("mil"))
        assertTrue(TwidgetStore.compactNumber(1250000, spain).contains("1,25"))
        assertTrue(TwidgetStore.compactNumber(1250000, mexico).contains("1.25"))
        assertTrue(TwidgetStore.compactNumber(1250000, Locale.GERMANY).contains("Mio"))
        assertNotEquals(TwidgetStore.compactNumber(125000, Locale.US), TwidgetStore.compactNumber(125000, Locale.forLanguageTag("en-IN")))
        assertNotEquals(TwidgetStore.compactNumber(1250000, Locale.US), TwidgetStore.compactNumber(1250000, Locale.FRANCE))
        for (tag in listOf("ca-ES", "es-ES", "es-MX", "en-US", "en-GB", "fr-FR", "fr-CA", "de-DE", "de-CH")) {
            val locale = Locale.forLanguageTag(tag)
            assertEquals(locale, AppLocales.formattingLocale(context(tag)))
            assertEquals("+" + AppLocales.compact(12500, locale), TwidgetStore.signedNumber(12500, locale))
            for (n in listOf(0L, -12500L, 999L, 999999L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                assertTrue("$tag/$n", AppLocales.compact(n, locale).isNotBlank())
            }
        }
    }

    @Test fun datesFollowRegionalOrderAndTimesHonorThePhoneClockPreference() {
        // Midday avoids a date change in the phone's local time zone.
        val timestamp = java.time.LocalDate.of(2026, 10, 24).atTime(13, 5)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val us = AppLocales.formatDate(timestamp, "MMMd", Locale.US, context("en-US"))
        val uk = AppLocales.formatDate(timestamp, "MMMd", Locale.UK, context("en-GB"))
        assertTrue(us, us.indexOf("Oct") < us.indexOf("24"))
        assertTrue(uk, uk.indexOf("24") < uk.indexOf("Oct"))
        for (tag in listOf("ca-ES", "es-ES", "es-MX", "en-US", "en-GB", "fr-FR", "fr-CA", "de-DE", "de-CH")) {
            val c = context(tag)
            val locale = Locale.forLanguageTag(tag)
            val time = AppLocales.formatDate(timestamp, "jm", locale, c)
            assertEquals(android.text.format.DateFormat.is24HourFormat(c), time.contains("13"))
            assertEquals(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(timestamp)),
                AppLocales.date(java.time.LocalDate.of(2026, 10, 24), locale))
        }
    }
}
