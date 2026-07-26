package com.tjg.twidget.schedule

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleMediaImportPolicyTest {
    @Test
    fun `accepts unknown or bounded reported sizes`() {
        assertTrue(ScheduleMediaImportPolicy.isReportedSizeAllowed(null))
        assertTrue(ScheduleMediaImportPolicy.isReportedSizeAllowed(-1L))
        assertTrue(
            ScheduleMediaImportPolicy.isReportedSizeAllowed(
                ScheduleMediaImportPolicy.MAX_MEDIA_BYTES,
            ),
        )
        assertFalse(
            ScheduleMediaImportPolicy.isReportedSizeAllowed(
                ScheduleMediaImportPolicy.MAX_MEDIA_BYTES + 1L,
            ),
        )
    }

    @Test
    fun `copy rejects an unknown-size stream once it crosses the limit`() {
        val output = ByteArrayOutputStream()

        assertThrows(IllegalStateException::class.java) {
            ScheduleMediaImportPolicy.copyLimited(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                output,
                maxBytes = 4,
            )
        }
        assertTrue(output.size() <= 4)
    }

    @Test
    fun `copy retains media at the exact limit`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()

        assertEquals(
            4L,
            ScheduleMediaImportPolicy.copyLimited(
                ByteArrayInputStream(bytes),
                output,
                maxBytes = 4,
            ),
        )
        assertArrayEquals(bytes, output.toByteArray())
    }
}
