package com.tjg.twidget.buildlogic

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/** Evaluated afresh for each build, including when the configuration cache is reused. */
abstract class DebugBuildTimestamp : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = ZonedDateTime.now(ZoneId.of("Europe/London"))
        .format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmm"))
}
