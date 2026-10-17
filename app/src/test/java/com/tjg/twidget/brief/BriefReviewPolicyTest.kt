package com.tjg.twidget.brief

import com.tjg.twidget.core.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BriefReviewPolicyTest {
    @Test fun localOnlyNeverStartsCloudEvenWithAKey() = runBlocking {
        for (key in listOf(false, true)) {
            assertEquals(BriefCoachGeneration.Failure(BriefReviewFailure.LOCAL_ONLY),
                BriefReviewPolicy.generate(BriefProviderMode.LOCAL, key) { error("Local must not use cloud") })
        }
    }
    @Test fun autoAndCloudNeedAKeyAndThenUseCloud() = runBlocking {
        var calls = 0
        for (mode in listOf(BriefProviderMode.AUTO, BriefProviderMode.CLOUD)) {
            assertEquals(BriefCoachGeneration.Failure(BriefReviewFailure.MISSING_KEY),
                BriefReviewPolicy.generate(mode, false) { error("No key") })
            assertEquals(BriefCoachGeneration.Failure(BriefReviewFailure.QUOTA),
                BriefReviewPolicy.generate(mode, true) { calls++; BriefCoachGeneration.Failure(BriefReviewFailure.QUOTA) })
        }
        assertEquals(2, calls)
    }
    @Test fun reviewCadenceSlowsAsTheTweetAges() {
        val hour = 3_600_000L
        val postedAt = 1_800_000_000_000L
        assertEquals(4 * hour, BriefReviewFreshness.lifetime(postedAt, postedAt + hour))
        assertEquals(12 * hour, BriefReviewFreshness.lifetime(postedAt, postedAt + 24 * hour))
        assertEquals(48 * hour, BriefReviewFreshness.lifetime(postedAt, postedAt + 7 * 24 * hour))
        assertEquals(12 * hour, BriefReviewFreshness.lifetime(0, postedAt))
    }
    @Test fun failureCategoriesDoNotExposeRawProviderResponses() {
        assertEquals(BriefReviewFailure.AUTHENTICATION, reviewFailure(HttpTransport.HttpException(403, "secret")))
        assertEquals(BriefReviewFailure.INVALID_REQUEST, reviewFailure(HttpTransport.HttpException(400, "secret")))
        assertEquals(BriefReviewFailure.QUOTA, reviewFailure(HttpTransport.HttpException(429, "secret")))
        assertEquals(BriefReviewFailure.SERVICE, reviewFailure(HttpTransport.HttpException(503, "secret")))
        assertEquals(BriefReviewFailure.NETWORK, reviewFailure(java.net.UnknownHostException("secret")))
        assertEquals(BriefReviewFailure.TIMEOUT, reviewFailure(java.net.SocketTimeoutException("secret")))
        assertEquals(BriefReviewFailure.BLOCKED, reviewFailure(BriefReviewException(BriefReviewFailure.BLOCKED)))
    }
}
