package com.tjg.twidget.brief

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage

/** System language identification uses the author's own words, never the app or quoted source locale. */
internal object BriefCoachWritingLanguage {
    fun identify(context: Context, text: String): String? {
        if (Build.VERSION.SDK_INT < 29 || text.isBlank()) return null
        return runCatching {
            val ownWords = text.replace(Regex("https?://\\S+|@\\w+|#\\S+"), " ").take(10_000)
            val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return null
            val result = classifier.detectLanguage(TextLanguage.Request.Builder(ownWords).build())
            if (result.localeHypothesisCount == 0) return null
            val best = result.getLocale(0)
            val score = result.getConfidenceScore(best)
            val second = if (result.localeHypothesisCount > 1) result.getConfidenceScore(result.getLocale(1)) else 0f
            best.language.takeIf { it.isNotBlank() && it != "und" && score >= 0.8f && second < 0.2f }
        }.getOrNull()
    }

    fun prepare(context: Context, input: BriefCoachInput): BriefCoachInput = input.copy(writingLanguages =
        input.entries.ifEmpty { listOf(input.text) }.mapIndexedNotNull { index, text ->
            identify(context, text)?.let { index + 1 to it }
        }.toMap())

    fun accepts(context: Context, input: BriefCoachInput, feedback: BriefCoachFeedback): Boolean =
        feedback.suggestions.all { suggestion ->
            val expected = input.writingLanguage(suggestion.quote)
            val actual = suggestion.rewrite?.let { identify(context, it) }
            expected == null || actual == null || expected == actual
        }
}
