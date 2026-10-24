package com.tjg.twidget.brief

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BriefCoachPipelineTest {
    @Test fun mediaLoadingIsInsideTheDeadline() = runBlocking {
        var inference = false
        val result = BriefCoachAiRunner.pipeline<Int>(timeoutMs = 60, load = {
            delay(300); listOf(1)
        }, release = {}, generate = { inference = true; BriefCoachGeneration.Unavailable })
        assertEquals(BriefCoachGeneration.Failure(BriefReviewFailure.TIMEOUT), result)
        assertFalse(inference)
    }

    @Test fun inferenceAndRetriesShareTheRemainingMediaBudgetAndReleaseImages() = runBlocking {
        val released = mutableListOf<Int>()
        var inference = false
        val result = BriefCoachAiRunner.pipeline(timeoutMs = 300, load = {
            delay(180); listOf(1, 2)
        }, release = { released += it }, generate = {
            inference = true; delay(180); BriefCoachGeneration.Unavailable
        })
        assertTrue(inference)
        assertEquals(BriefCoachGeneration.Failure(BriefReviewFailure.TIMEOUT), result)
        assertEquals(listOf(1, 2), released)
    }

    @Test fun successfulRequestsAndExternalCancellationReleaseOwnedImages() = runBlocking {
        val released = mutableListOf<Int>()
        assertEquals(BriefCoachGeneration.Unavailable, BriefCoachAiRunner.pipeline(load = { listOf(1) },
            release = { released += it }, generate = { BriefCoachGeneration.Unavailable }))
        val started = CompletableDeferred<Unit>()
        val job = launch {
            BriefCoachAiRunner.pipeline(load = { listOf(2) }, release = { released += it }, generate = {
                started.complete(Unit); awaitCancellation()
            })
        }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1, 2), released)
    }

    @Test fun blockingProviderWorkIsInterruptibleWithinTheBudget() = runBlocking {
        val result = BriefCoachAiRunner.pipeline<Int>(timeoutMs = 100, load = { emptyList() }, release = {}, generate = {
            runInterruptible(Dispatchers.IO) { Thread.sleep(5000) }
            BriefCoachGeneration.Unavailable
        })
        assertEquals(BriefCoachGeneration.Failure(BriefReviewFailure.TIMEOUT), result)
    }
}
