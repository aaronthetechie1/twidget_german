package com.tjg.twidget.brief

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.TwidgetStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BriefPublishedReviewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun typedMeasurementsValidateAndRenderOnAndroid() = withAccount { account ->
        val subject = subject(account)
        val result = result(subject).let { it.copy(feedback = it.feedback.copy(
            possibleExplanation = "You received {{stat:performance:views}} and {{stat:performance:likes}}.")) }
        val restored = BriefCoachResultCodec.decode(BriefCoachResultCodec.encode(result), subject.input)
        assertEquals(result, restored)
        val strings = BriefStrings.from(context)
        val shown = BriefReviewCopy.clean(restored.feedback, subject.input, strings).possibleExplanation
        assertTrue(shown.contains(strings.number(1000)))
        assertFalse(shown.contains("{{"))
        assertFalse(BriefReviewNumbers.accepts(result.feedback.copy(possibleExplanation = "You received 1,000 likes."), subject.input.packet()))
        assertTrue(BriefReviewNumbers.accepts(result.feedback.copy(possibleExplanation = "The image names Pixel 9 and version 1.3.1."), subject.input.packet()))
    }

    @Test fun savedReviewSurvivesDatabaseReopenAndMissingAnalyticsWithoutGeneratingAgain() = withAccount { account -> runBlocking {
        val subject = subject(account)
        val result = result(subject)
        val saved = BriefPublishedReviewGeneration.getOrGenerate(context, subject) { result }!!
        BriefPublishedReviewStore.closeForTest()
        val alias = subject.target.copy(postUrl = "https://twitter.com/$account/status/123?ref=cache")
        assertEquals(saved, BriefPublishedReviewStore.read(context, alias))
        val changed = subject.copy(post = subject.post!!.copy(views = 9999))
        val reopened = BriefPublishedReviewGeneration.getOrGenerate(context, changed) { error("A fresh saved review must not generate again") }!!
        assertEquals(1000L, reopened.subject.post!!.views)
        assertEquals(result, reopened.result)
        assertTrue(BriefPublishedReviewStore.hasReviews(context, account))
        assertFalse(BriefPublishedReviewStore.hasReviews(context, account, setOf(alias.postUrl!!)))
        assertEquals(listOf(subject.post), BriefPublishedReviewStore.posts(context, account, 50))

        val prefs = context.getSharedPreferences("twidget_brief_settings", Context.MODE_PRIVATE)
        val before = prefs.all["content_tweet_reviews"]
        prefs.edit().putBoolean("content_tweet_reviews", true).commit()
        try {
            // This account has no analytics cache at all: only the stored review can satisfy this page.
            assertNull(AnalyticsClient.cached(context, account))
            val reviewIntent = BriefTweetReviewActivity.intent(context, alias)
            val libraryIntent = BriefReviewListActivity.intent(context, account, emptySet(), emptySet())
            listOf(reviewIntent, reviewIntent, libraryIntent).forEach { intent ->
            val expected = if (intent === libraryIntent) subject.post!!.text else context.getString(R.string.review_next_steps_intro)
            ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
                val deadline = System.currentTimeMillis() + 5_000
                var complete = false
                do {
                    scenario.onActivity { activity -> complete = texts(activity.findViewById(R.id.review_root)).any { it == expected } }
                    if (!complete) Thread.sleep(60)
                } while (!complete && System.currentTimeMillis() < deadline)
                assertTrue("Saved results should open without fresh analytics", complete)
                scenario.onActivity { activity -> assertFalse(texts(activity.findViewById(R.id.review_root)).any { it == context.getString(R.string.review_generating) }) }
            }
            }
        } finally {
            prefs.edit().apply { if (before is Boolean) putBoolean("content_tweet_reviews", before) else remove("content_tweet_reviews") }.commit()
        }
    } }

    @Test fun viewersShareOneAttemptAndClosingTheFirstViewerDoesNotDiscardIt() = withAccount { account -> runBlocking {
        withTimeout(10_000) {
            val subject = subject(account)
            val calls = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val first = async { BriefPublishedReviewGeneration.getOrGenerate(context, subject) {
                calls.incrementAndGet(); started.complete(Unit); finish.await(); result(subject)
            } }
            started.await()
            first.cancelAndJoin()
            val second = async { BriefPublishedReviewGeneration.getOrGenerate(context, subject) {
                calls.incrementAndGet(); result(subject)
            } }
            finish.complete(Unit)
            assertNotNull(second.await())
            assertEquals(1, calls.get())
        }
    } }

    @Test fun aLanguageChangeReplacesEvenAFreshSavedReviewAndOnlyReusesMatchingLanguage() = withAccount { account -> runBlocking {
        val english = subject(account, "en")
        val first = BriefPublishedReviewGeneration.getOrGenerate(context, english) { result(english) }!!
        assertTrue(BriefReviewFreshness.canReuse(first, "EN"))
        assertFalse(BriefReviewFreshness.canReuse(first, "fr"))
        val french = BriefReviewSubjects.fromSaved(first, BriefStrings.from(context, "fr"))
        assertEquals("fr", french.input.language)
        assertEquals(english.input.text, french.input.text)
        assertEquals(english.input.reviewEvidence, french.input.reviewEvidence)
        assertNotEquals(english.input.report.limitations, french.input.report.limitations)
        assertNotEquals(english.input.reviewCitations, french.input.reviewCitations)
        var calls = 0
        val next = BriefPublishedReviewGeneration.getOrGenerate(context, french) {
            calls++; result(french).copy(feedback = result(french).feedback.copy(possibleExplanation = "Votre introduction manque peut-être de contexte."))
        }!!
        assertEquals(1, calls)
        assertEquals("fr", next.subject.input.language)
        assertTrue(next.savedAt > first.savedAt)
        BriefPublishedReviewStore.closeForTest()
        assertEquals(next, BriefPublishedReviewStore.read(context, french.target))
        assertEquals(next, BriefPublishedReviewGeneration.getOrGenerate(context, french) { error("The French review should be reused") })
        val again = BriefPublishedReviewGeneration.getOrGenerate(context, english) { calls++; result(english) }!!
        assertEquals(2, calls)
        assertEquals("en", again.subject.input.language)
    } }

    @Test fun changingLanguageDuringGenerationCannotJoinOrSaveTheOlderLanguageAttempt() = withAccount { account -> runBlocking {
        withTimeout(10_000) {
            val english = subject(account, "en")
            val french = english.copy(input = english.input.copy(language = "fr"))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val first = async {
                BriefPublishedReviewGeneration.getOrGenerate(context, english) {
                    started.complete(Unit)
                    // Simulate an HTTP call that returns after its coroutine was cancelled.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { finish.await() }
                    result(english)
                }
            }
            started.await()
            val next = BriefPublishedReviewGeneration.getOrGenerate(context, french) { result(french) }!!
            finish.complete(Unit)
            first.join()
            assertTrue(first.isCancelled)
            assertEquals("fr", next.subject.input.language)
            assertEquals(next, BriefPublishedReviewStore.read(context, french.target))
        }
    } }

    @Test fun failedAttemptsCanRetryAndRemovedAccountsCannotReceiveLateResults() = withAccount { account -> runBlocking {
        val subject = subject(account)
        assertNull(BriefPublishedReviewGeneration.getOrGenerate(context, subject) { BriefCoachGeneration.Unavailable })
        val generation = TwidgetStore.accountGeneration(context, account)
        TwidgetStore.removeAccount(context, account)
        TwidgetStore.addAccount(context, account)
        assertNull(BriefPublishedReviewStore.save(context, subject, result(subject), generation))
        assertNotNull(BriefPublishedReviewGeneration.getOrGenerate(context, subject) { result(subject) })
        TwidgetStore.removeAccount(context, account)
        TwidgetStore.addAccount(context, account)
        assertNull(BriefPublishedReviewStore.read(context, subject.target))
    } }

    @Test fun reviewsRemainAccountScopedAndMigrateWithTheAccount() = withAccount { old -> withAccount { next -> runBlocking {
        val subject = subject(old)
        BriefPublishedReviewGeneration.getOrGenerate(context, subject) { result(subject) }
        assertNull(BriefPublishedReviewStore.read(context, subject.target.copy(username = next)))
        BriefPublishedReviewStore.copyAccount(context, old, next)
        BriefPublishedReviewStore.removeAccount(context, old)
        assertNotNull(BriefPublishedReviewStore.read(context, subject.target.copy(username = next)))
        assertNull(BriefPublishedReviewStore.read(context, subject.target))
    } } }

    @Test fun expiredReviewsRefreshAndManualRefreshBypassesFreshnessWithoutLosingGoodResults() = withAccount { account -> runBlocking {
        val subject = subject(account)
        val original = result(subject)
        val now = System.currentTimeMillis()
        val old = BriefPublishedReviewStore.save(context, subject, original,
            TwidgetStore.accountGeneration(context, account), now = now - 13 * 3_600_000L)!!
        assertFalse(BriefReviewFreshness.isFresh(old))
        assertFalse(BriefReviewFreshness.isFresh(old, old.savedAt - 1))
        assertTrue(BriefReviewFreshness.isFresh(old, old.savedAt + 4 * 3_600_000L - 1))
        assertFalse(BriefReviewFreshness.isFresh(old, old.savedAt + 4 * 3_600_000L))
        val updated = subject.copy(post = subject.post!!.copy(views = 5000),
            input = subject.input.copy(reviewEvidence = subject.input.reviewEvidence!!.copy(post = subject.post!!.copy(views = 5000))))
        val failed = BriefPublishedReviewGeneration.attempt(context, updated) { BriefCoachGeneration.Failure(BriefReviewFailure.QUOTA) }
        assertEquals(BriefReviewFailure.QUOTA, failed.failure)
        assertEquals(old, failed.review)
        assertEquals(old, BriefPublishedReviewStore.read(context, subject.target))
        var preparations = 0
        val next = BriefPublishedReviewGeneration.attempt(context, subject, prepare = { preparations++; updated }) {
            assertEquals(5000L, it.post!!.views)
            original.copy(feedback = original.feedback.copy(possibleExplanation = "Your tweet has gained more views."))
        }.review!!
        assertEquals(1, preparations)
        assertEquals(5000L, next.subject.post!!.views)
        assertTrue(next.savedAt > old.savedAt)
        assertTrue(BriefReviewFreshness.isFresh(next))
        assertEquals(next, BriefPublishedReviewGeneration.attempt(context, updated) { error("Fresh review must be reused") }.review)
        val manual = BriefPublishedReviewGeneration.attempt(context, updated, force = true) { original }.review!!
        assertTrue(manual.savedAt > next.savedAt)
        // A late result based on the original row cannot overwrite the manual refresh.
        assertEquals(manual, BriefPublishedReviewStore.save(context, subject, original,
            TwidgetStore.accountGeneration(context, account), replaceSavedAt = old.savedAt))
    } }

    @Test fun simultaneousManualRefreshesShareTheSameAttempt() = withAccount { account -> runBlocking {
        withTimeout(10_000) {
            val subject = subject(account)
            BriefPublishedReviewGeneration.getOrGenerate(context, subject) { result(subject) }
            val calls = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val first = async { BriefPublishedReviewGeneration.attempt(context, subject, force = true) {
                calls.incrementAndGet(); started.complete(Unit); finish.await(); result(subject)
            } }
            started.await()
            val second = async { BriefPublishedReviewGeneration.attempt(context, subject, force = true) {
                calls.incrementAndGet(); result(subject)
            } }
            kotlinx.coroutines.yield()
            finish.complete(Unit)
            assertEquals(first.await().review, second.await().review)
            assertEquals(1, calls.get())
        }
    } }

    @Test fun unchangedEvidenceRenewsTheCheckWithoutGeneratingAndManualRefreshStillGenerates() = withAccount { account -> runBlocking {
        val subject = subject(account)
        val now = System.currentTimeMillis()
        val saved = BriefPublishedReviewStore.save(context, subject, result(subject),
            TwidgetStore.accountGeneration(context, account), now = now - 13 * 3_600_000L)!!
        var preparations = 0
        val checked = BriefPublishedReviewGeneration.attempt(context, subject, prepare = {
            preparations++; subject.copy(input = subject.input.copy(observedAt = now + 1))
        }) { error("Unchanged evidence must not call a model") }.review!!
        assertEquals(1, preparations)
        assertEquals(saved.savedAt, checked.savedAt)
        assertEquals(saved.subject, checked.subject)
        assertEquals(saved.result, checked.result)
        assertTrue(checked.checkedAt > saved.checkedAt)
        assertTrue(BriefReviewFreshness.isFresh(checked))
        BriefPublishedReviewStore.closeForTest()
        assertEquals(checked, BriefPublishedReviewStore.read(context, subject.target))
        BriefPublishedReviewGeneration.attempt(context, subject, prepare = { error("The check interval should suppress repeated analytics polling") }) {
            error("A recently checked review must be reused")
        }
        var calls = 0
        val manual = BriefPublishedReviewGeneration.attempt(context, subject, force = true) { calls++; result(subject) }.review!!
        assertEquals(1, calls)
        assertTrue(manual.savedAt > checked.savedAt)
    } }

    @Test fun durableDraftAdviceSurvivesReopenAndSeparatesRevisionsLanguagesEvidenceAndAccounts() = withAccount { account ->
        val store = com.tjg.twidget.schedule.ScheduleStore(context)
        val draft = store.create(com.tjg.twidget.schedule.ScheduledPost(provider = com.tjg.twidget.schedule.ScheduleProvider.LOCAL_REMINDER,
            accountUsername = account, scheduledAt = null, thread = listOf(com.tjg.twidget.schedule.ScheduleThreadItem(text = "Finally shipped this. Thoughts?"))))
        try {
            val strings = BriefStrings.from(context)
            val input = BriefCoachAi.draft(draft, null, BriefPostCoach.draft(draft, null, strings, System.currentTimeMillis()), strings, System.currentTimeMillis())
            val subject = BriefReviewSubject(BriefReviewTarget(account, draftId = draft.id), input, draft = draft)
            val key = BriefReviewResults.key(subject, BriefProviderMode.CLOUD, BriefNanoModelMode.AUTO_STABLE)
            val generation = TwidgetStore.accountGeneration(context, account)
            val saved = BriefPublishedReviewStore.saveDraft(context, subject, key, result(subject), generation)!!
            BriefPublishedReviewStore.closeForTest()
            assertEquals(saved, BriefPublishedReviewStore.readDraft(context, subject, key))
            assertNull(BriefPublishedReviewStore.readDraft(context, subject.copy(target = subject.target.copy(username = "another_account")), key))
            for (changed in listOf(subject.copy(draft = draft.copy(updatedAt = draft.updatedAt + 1)),
                subject.copy(input = input.copy(language = "another-language")), subject.copy(input = input.copy(text = "Changed evidence and wording")))) {
                val changedKey = BriefReviewResults.key(changed, BriefProviderMode.CLOUD, BriefNanoModelMode.AUTO_STABLE)
                assertNotEquals(key, changedKey)
                assertNull(BriefPublishedReviewStore.readDraft(context, changed, changedKey))
                BriefPublishedReviewStore.saveDraft(context, subject, key, result(subject), generation)
            }
            assertNull(BriefPublishedReviewStore.readDraft(context, subject, key, saved.savedAt + 8 * 24 * 3_600_000L))
            BriefPublishedReviewStore.saveDraft(context, subject, key, result(subject), generation)
            TwidgetStore.removeAccount(context, account)
            TwidgetStore.addAccount(context, account)
            assertNull(BriefPublishedReviewStore.readDraft(context, subject, key))
            assertNull(BriefPublishedReviewStore.saveDraft(context, subject, key, result(subject), generation))
        } finally { store.removeAll(setOf(draft.id)) }
    }

    private fun subject(account: String, language: String = com.tjg.twidget.core.AppLocales.DEFAULT): BriefReviewSubject {
        val now = System.currentTimeMillis()
        val post = PostSummary("https://x.com/$account/status/123", "Finally shipped this. Thoughts?", 1000, 12, 4, 5, 2, 23,
            now - 36 * 3_600_000L, "", "Saved review fixture", account, "", fullText = "Finally shipped this. Thoughts?")
        val analytics = AnalyticsClient.analyticsFromPosts(account, 100, 1, false, listOf(post), cachedAt = now)
        val strings = BriefStrings.from(context, language)
        val report = BriefPostCoach.review(post, analytics, strings, now)!!
        val evidence = BriefReviewEvidence.create(post, analytics, emptyList())
        val input = BriefCoachAi.review(post, analytics, report, strings, now).copy(reviewEvidence = evidence, reviewCitations = evidence.citations(strings))
        return BriefReviewSubject(BriefReviewTarget(account, postUrl = post.url), input, post = post)
    }
    private fun result(subject: BriefReviewSubject) = BriefCoachGeneration.Success(BriefCoachFeedback("Your opening may need more context.",
        listOf(BriefCoachSuggestion("Name what you shipped.", "Your wording leaves the subject implicit.", "Finally shipped this.", null, false,
            subject.input.packet().citations.filter { it.id == "subject" })), null), false)
    private fun withAccount(block: (String) -> Unit) {
        val account = "rv_${System.nanoTime().toString().takeLast(8)}"
        TwidgetStore.addAccount(context, account)
        try { block(account) } finally { TwidgetStore.removeAccount(context, account) }
    }
    private fun texts(view: android.view.View): Sequence<String> = sequence {
        if (view is android.widget.TextView) yield(view.text.toString())
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(texts(view.getChildAt(index)))
    }
}
