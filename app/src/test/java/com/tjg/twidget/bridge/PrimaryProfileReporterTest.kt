package com.tjg.twidget.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryProfileReporterTest {
    @Test fun onlyTheSelectedPublicProfileIsReported() {
        assertEquals("main", primaryProfileUsername(true, "@Main", "main", false))
        assertNull(primaryProfileUsername(true, "main", "tracked", false))
        assertNull(primaryProfileUsername(true, "main", "main", true))
        assertNull(primaryProfileUsername(true, "main", "main", null))
        assertNull(primaryProfileUsername(false, "main", "main", false))
        assertNull(primaryProfileUsername(true, "", "", false))
        assertNull(primaryProfileUsername(true, "invalid/name", "invalid/name", false))
    }
}
