package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test

class BriefRunCacheTest {
    private val now = 1_800_000_000_000L
    private val strings = TestBriefStrings()
    private fun run(id: String = "growth", provider: BriefProviderUsed = BriefProviderUsed.LOCAL,
        at: Long = now - 60_000L) = BriefSnapshot(
        username = "tester", generatedAt = at, sourceSyncedAt = 123L, analyticsCachedAt = 456L,
        followerScanCompletedAt = 789L, followers = 100, following = 20, posts = 30,
        followersToday = 1, followersWeek = 5, topFollowerRanks = emptyMap(),
        cards = listOf(BriefCard(id, BriefCardType.GROWTH, "One new follower", "You gained 1 follower.", 90,
            narrativeGenerated = true)),
        headline = "Your progress", subheading = "You gained 1 follower.", shortDescription = "One new follower",
        summaryGenerated = true, language = "en", engineVersion = 20, contextFingerprint = id,
        providerUsed = provider, aiGeneratedAt = at,
    )

    @Test fun `recent runs are bounded deduplicated and exclude failed or expired results`() {
        val runs = (0..6).map { run("card$it", at = now - it * 1_000L) }
        val failed = run("failure").copy(summaryGenerated = false)
        val expired = run("expired", at = now - BriefAiCachePolicy.LOCAL_TTL_MS)
        val recent = BriefRunCache.recent(listOf(failed, expired, runs.first()) + runs, now)
        assertEquals(4, recent.size)
        assertEquals(listOf("card0", "card1", "card2", "card3"), recent.map { it.cards.single().id })
    }

    @Test fun `returning to an earlier content configuration reuses its own run`() {
        val first = run("growth")
        val second = run("post")
        val source = first.copy(providerUsed = BriefProviderUsed.TEMPLATE, summaryGenerated = false,
            cards = first.cards.map { it.copy(narrativeGenerated = false, score = 70) })
        val restored = BriefRunCache.find(listOf(second, first), source, BriefProviderMode.AUTO, strings, now)!!
        assertTrue(restored.hasGeneratedNarrative)
        assertEquals(first.aiGeneratedAt, restored.aiGeneratedAt)
        assertEquals(70, restored.cards.single().score)
    }

    @Test fun `cache never crosses accounts languages providers evidence or context`() {
        val cached = run()
        val variants = listOf(cached.copy(username = "other"), cached.copy(language = "de"),
            cached.copy(engineVersion = 21), cached.copy(analyticsCachedAt = 457L),
            cached.copy(sourceSyncedAt = 124L), cached.copy(followerScanCompletedAt = 790L),
            cached.copy(contextFingerprint = "new goal or schedule"), cached.copy(followersToday = 2),
            cached.copy(cards = cached.cards.map { it.copy(actionData = "different post") }))
        variants.forEach { source ->
            assertNull(BriefRunCache.find(listOf(cached), source, BriefProviderMode.AUTO, strings, now))
        }
        assertNull(BriefRunCache.find(listOf(cached), cached, BriefProviderMode.CLOUD, strings, now))
        val cloud = run(provider = BriefProviderUsed.CLOUD)
        assertNull(BriefRunCache.find(listOf(cloud), cloud, BriefProviderMode.LOCAL, strings, now))
        assertNotNull(BriefRunCache.find(listOf(cloud), cloud, BriefProviderMode.AUTO, strings, now))
    }

    @Test fun `cloud and local runs retain their separate expiry windows`() {
        val old = now - 3 * 60 * 60_000L
        val local = run(at = old)
        val cloud = run(provider = BriefProviderUsed.CLOUD, at = old)
        assertEquals(listOf(local), BriefRunCache.recent(listOf(local, cloud), now))
        assertTrue(BriefRunCache.recent(listOf(local), now - 4 * 60 * 60_000L).isEmpty())
    }

    @Test fun `foreground failures back off across reopen but changed settings retry immediately`() {
        val policy = BriefPreparationPolicy()
        assertTrue(policy.canAttempt("account-local-en", now))
        policy.attempted("account-local-en", now)
        assertFalse(policy.canAttempt("account-local-en", now + 60_000L))
        assertTrue(policy.canAttempt("account-cloud-en", now + 60_000L))
        assertTrue(policy.canAttempt("account-local-en", now + 15 * 60_000L))
        policy.succeeded()
        assertTrue(policy.canAttempt("account-local-en", now + 60_000L))
    }
}
