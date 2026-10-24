package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.data.TwidgetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class BriefPublishedReviewAttempt(val review: BriefSavedReview?, val failure: BriefReviewFailure? = null)
internal class BriefReviewException(val reason: BriefReviewFailure) : Exception()

/** Viewers share refreshes, including their analytics fetch; closing a page keeps the result. */
internal object BriefPublishedReviewGeneration {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Running(val language: String, val task: Deferred<BriefPublishedReviewAttempt>)
    private val running = mutableMapOf<String, Running>()

    suspend fun getOrGenerate(context: Context, subject: BriefReviewSubject,
        generate: suspend () -> BriefCoachGeneration): BriefSavedReview? =
        attempt(context, subject, generate = { generate() }).let { if (it.failure == null) it.review else null }

    suspend fun attempt(context: Context, subject: BriefReviewSubject, force: Boolean = false,
        prepare: suspend () -> BriefReviewSubject = { subject },
        generate: suspend (BriefReviewSubject) -> BriefCoachGeneration): BriefPublishedReviewAttempt {
        val app = context.applicationContext
        val target = subject.target.copy(username = TwidgetStore.canonicalAccount(app, subject.target.username))
        val language = subject.input.language
        val before = BriefPublishedReviewStore.read(app, target)
        if (!force && before != null && BriefReviewFreshness.canReuse(before, language)) return BriefPublishedReviewAttempt(before)
        val generation = TwidgetStore.accountGeneration(app, target.username)
        val key = "${target.username.lowercase(java.util.Locale.US)}|$generation|${BriefPublishedReviewStore.postId(target.postUrl.orEmpty())}"
        val task = synchronized(running) {
            val previous = running[key]
            previous?.takeIf { it.language.equals(language, ignoreCase = true) }?.task ?: scope.async(start = CoroutineStart.LAZY) {
                val saved = BriefPublishedReviewStore.read(app, target)
                // A completed replacement between lookup and joining satisfies this request too.
                if (saved != null && BriefReviewFreshness.matchesLanguage(saved, language) &&
                    (saved.savedAt != before?.savedAt || (!force && BriefReviewFreshness.isFresh(saved)))) {
                    BriefPublishedReviewAttempt(saved)
                } else try {
                    val current = prepare()
                    if (!current.input.language.equals(language, ignoreCase = true)) throw CancellationException("Review language changed")
                    if (!force && saved != null && BriefReviewFreshness.matchesLanguage(saved, language) &&
                        BriefReviewEvidenceFingerprint.same(saved.subject, current)) {
                        val checked = BriefPublishedReviewStore.markChecked(app, saved, generation)
                        return@async BriefPublishedReviewAttempt(checked, if (checked == null) BriefReviewFailure.POST_UNAVAILABLE else null)
                    }
                    val result = generate(current)
                    // An older-language request can finish its HTTP call after cancellation.
                    // It must not replace the review requested in the new app language.
                    val coroutine = currentCoroutineContext()
                    if (result is BriefCoachGeneration.Success) {
                        val updated = synchronized(running) {
                            coroutine.ensureActive()
                            BriefPublishedReviewStore.save(app, current, result, generation, saved?.savedAt)
                        }
                        BriefPublishedReviewAttempt(updated, if (updated == null) BriefReviewFailure.POST_UNAVAILABLE else null)
                    } else BriefPublishedReviewAttempt(saved, (result as? BriefCoachGeneration.Failure)?.reason ?: BriefReviewFailure.UNKNOWN)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    BriefPublishedReviewAttempt(saved, (error as? BriefReviewException)?.reason ?: reviewFailure(error))
                }
            }.also { task ->
                running[key] = Running(language, task)
                previous?.task?.cancel()
                task.invokeOnCompletion { synchronized(running) { if (running[key]?.task === task) running.remove(key) } }
            }
        }
        return task.await()
    }
}
