package com.tjg.twidget.followers

import com.tjg.twidget.providers.TwitterApisAccessSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersDialogPolicyTest {
    @Test
    fun addKeyActionIsHiddenForPersonalKeys() {
        assertFalse(shouldShowAddApiKeyAction(TwitterApisAccessSource.PERSONAL))
        assertTrue(shouldShowAddApiKeyAction(null))
    }

    @Test
    fun optedInDialogDescribesTheBridgeEvenWhenAPersonalKeyExists() {
        assertTrue(
            selectTopFollowersScanDialogMode(
                shareHistory = true,
                accessSource = TwitterApisAccessSource.PERSONAL,
            ) == TopFollowersScanDialogMode.SHARED,
        )
    }
}
