package com.tjg.twidget.brief

internal object BriefVoicePolicy {
    const val PUNCTUATION_INSTRUCTION = "Never use em dashes (U+2014) in generated text, in any language, including proposed tweet rewrites. Use commas, colons, parentheses or separate sentences instead. Only verbatim source quotations retain their original punctuation. "

    const val INSTRUCTION = "Speak directly to the reader as you and your (the natural second-person equivalent in the app language). " +
        PUNCTUATION_INSTRUCTION +
        "Never narrate as the user with I, my, we or our, or call them the account or the user. " +
        "Use digits for every count, percentage, change and duration: 1 follower, 1,200 engagements, 3 days, 5%. Never spell statistics out in words. " +
        "Write like a helpful person: short, specific, natural sentences grounded in the supplied facts. " +
        "Prefer 'You received 1,200 engagements' to 'The account has accumulated a total of twelve hundred engagements'. " +
        "Avoid generic self-descriptions such as 'I share content regularly with a growing audience', empty praise and bureaucratic wording. "

    private val quoted = Regex("\"[^\"]*\"|“[^”]*”")
    private val emDash = Regex("[\\p{Zs}\\t]*\u2014+[\\p{Zs}\\t]*")

    /** Last-mile guard for model-authored copy, including saved generations. Never apply to source evidence. */
    fun withoutEmDashes(value: String): String = emDash.replace(value) { match ->
        val before = value.getOrNull(match.range.first - 1)
        val after = value.getOrNull(match.range.last + 1)
        when {
            before == null || after == null -> ""
            before == '\n' || after == '\n' -> ""
            after in ",.;:!?…)]}" -> ""
            before in ",.;:!?…([{" -> " "
            else -> ", "
        }
    }
    private val narrator = Regex("\\b(?:I(?:['’](?:m|ve|ll|d))?|my|mine|we(?:['’](?:re|ve|ll|d))?|our|ours|(?:the|this) (?:account|user))\\b", RegexOption.IGNORE_CASE)
    private val writtenNumber = Regex("\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|billion)\\b", RegexOption.IGNORE_CASE)

    /** Reject noncompliant narration instead of turning it into apparently verified copy. Quotes retain the author's voice. */
    fun accepts(value: String, strings: BriefStrings): Boolean {
        if (!strings.isEnglish) return true // English pronouns and number words are not rules for other languages.
        val narration = quoted.replace(value, "")
        return !narrator.containsMatchIn(narration) && !writtenNumber.containsMatchIn(narration)
    }
}
