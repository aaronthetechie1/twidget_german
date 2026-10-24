package com.tjg.twidget.data

import com.tjg.twidget.bridge.HistoryPool
import com.tjg.twidget.core.NetworkResponseParsers
import org.junit.Assert.*
import org.junit.Test

class SharedHistoryIdentityTest {
    private val samples = """[{"ts":1791676800000,"followers":999}]"""
    @Test fun sharedHistoryRequiresTheSamePermanentId() {
        assertNull(HistoryPool.ownedHistory("""{"userId":"999","history":$samples}""", "123"))
        assertNull(HistoryPool.ownedHistory("""{"history":$samples}""", "123"))
        assertNull(HistoryPool.ownedHistory("""{"userId":"123","history":$samples}""", ""))
        assertEquals(999L, HistoryPool.ownedHistory("""{"userId":"123","history":$samples}""", "123")!!.single().followers)
    }
    @Test fun profileResponsesCannotAttachAnotherAccountsOrUnboundHistory() {
        val profile = """"user":{"userId":"123","userName":"renamed","followersCount":10}"""
        assertTrue(NetworkResponseParsers.parseBridgeProfile("""{$profile,"historyUserId":"999","history":$samples}""").history.isEmpty())
        assertTrue(NetworkResponseParsers.parseBridgeProfile("""{$profile,"history":$samples}""").history.isEmpty())
        assertEquals(1, NetworkResponseParsers.parseBridgeProfile("""{$profile,"historyUserId":"123","history":$samples}""").history.size)
    }
}
