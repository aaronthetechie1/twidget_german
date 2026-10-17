package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.PublicUrlMedia
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduleThreadItem
import com.tjg.twidget.schedule.ScheduledPost

/** A rewrite opens a new unsaved composer session; it never edits the reviewed draft. */
internal object BriefReviewRewrite {
    fun entryIndex(subject: BriefReviewSubject, suggestion: BriefCoachSuggestion): Int? {
        if (suggestion.rewrite.isNullOrBlank() || !subject.input.complete) return null
        val draft = subject.draft ?: return 0.takeIf { subject.post?.fullText != null }
        if (draft.thread.size == 1) return 0
        return draft.thread.indices.filter { index ->
            suggestion.quote.isNotBlank() && draft.thread[index].text.contains(suggestion.quote)
        }.singleOrNull()
    }

    fun seed(username: String, rewrite: String, entryIndex: Int, draft: ScheduledPost? = null,
        post: PostSummary? = null, expectedUpdatedAt: Long = 0L): List<ScheduleThreadItem>? {
        if (rewrite.isBlank() || rewrite.length > 1000 || (draft == null) == (post == null)) return null
        if (draft != null) {
            if (!BriefPostCoach.account(draft).trimStart('@').equals(username.trimStart('@'), true) ||
                draft.status != ScheduleStatus.DRAFT || draft.deletedAt != null || draft.updatedAt != expectedUpdatedAt ||
                entryIndex !in draft.thread.indices) return null
            return draft.thread.mapIndexed { index, item -> ScheduleThreadItem(
                text = if (index == entryIndex) rewrite else item.text, media = item.media.toList()) }
        }
        post ?: return null
        if (!post.authorUserName.trimStart('@').equals(username.trimStart('@'), true) || post.fullText == null || entryIndex != 0) return null
        // Public analytics expose preview frames for video/GIF, not uploadable original video files.
        val images = post.media.filter { it.type.lowercase(java.util.Locale.ROOT) in setOf("photo", "image") }
            .filter { it.url.startsWith("https://") }.map { PublicUrlMedia(url = it.url) }
        val quotedUrl = post.quotedPost?.url?.takeIf { BriefPublishedReviewStore.postId(it) != null }
        val quotedId = quotedUrl?.let(BriefPublishedReviewStore::postId)
        val alreadyLinked = quotedId != null && Regex("https://(?:www\\.)?(?:x\\.com|twitter\\.com)/[^\\s]+")
            .findAll(rewrite).any { BriefPublishedReviewStore.postId(it.value.trimEnd('.', ',', ')')) == quotedId }
        val text = if (quotedUrl != null && !alreadyLinked) "$rewrite\n\n$quotedUrl" else rewrite
        return listOf(ScheduleThreadItem(text = text, media = images))
    }
}
