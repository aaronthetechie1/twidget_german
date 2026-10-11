package com.tjg.twidget.core

import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderCancellationTest {
    @Test fun cancellationDoesNotStartOptionalProvider() {
        var contacted = false
        try {
            ProviderFallback.directThenOptionalFallback<Int>(
                direct = { throw CancellationException("screen closed") },
                fallback = { contacted = true; 1 },
            )
            error("Expected cancellation")
        } catch (_: CancellationException) { assertFalse(contacted) }
    }
}
