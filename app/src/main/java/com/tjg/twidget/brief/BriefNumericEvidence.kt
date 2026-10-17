package com.tjg.twidget.brief

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Formatting may change; a count's value, sign and percentage unit may not. */
internal object BriefNumericEvidence {
    fun accepts(value: String, evidence: String, strings: BriefStrings): Boolean {
        return accepts(value, evidence, strings.locale)
    }

    fun accepts(value: String, evidence: String, locale: Locale): Boolean {
        val allowed = facts(evidence, locale).toSet()
        return facts(value, locale).all { it in allowed }
    }

    private fun facts(value: String, locale: Locale): List<String> {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val grouping = symbols.groupingSeparator
        // French number formatting uses a nonbreaking space; accept its ordinary-space equivalent too.
        val spaceGrouping = grouping.isWhitespace() || grouping == '\u00a0'
        val percentage = "(?:[\\p{Zs}\\t]*%)?"
        val pattern = if (spaceGrouping) Regex("[+-]?\\d+(?:[.,]\\d+)*(?:[ \\u00a0\\u202f]\\d{3}(?!\\d))*$percentage")
            else Regex("[+-]?\\d+(?:[.,]\\d+)*$percentage")
        return pattern.findAll(value).map { match ->
            val percent = match.value.endsWith('%')
            var number = match.value.removeSuffix("%").trim()
            if (spaceGrouping) number = number.replace(Regex("[ \\u00a0\\u202f]"), "")
            else {
                val unsigned = number.removePrefix("+").removePrefix("-")
                val integer = unsigned.substringBefore(symbols.decimalSeparator)
                // Remove grouping only when it has the expected three-digit structure.
                if (integer.contains(grouping)) {
                    val grouped = Regex("\\d{1,3}(?:${Regex.escape(grouping.toString())}\\d{3})+")
                    if (!grouped.matches(integer)) return@map "literal:${match.value}"
                    number = number.replace(grouping.toString(), "")
                }
            }
            if (symbols.decimalSeparator != '.') number = number.replace(symbols.decimalSeparator, '.')
            val canonical = runCatching { BigDecimal(number).stripTrailingZeros().toPlainString() }
                .getOrElse { "literal:${match.value}" }
            canonical + if (percent) "%" else ""
        }.toList()
    }
}

/** Check factual narration, not proposed experiments or the author's suggested tweet. */
internal object BriefReviewNumbers {
    fun accepts(feedback: BriefCoachFeedback, packet: BriefCoachPacket): Boolean =
        BriefReviewMeasurements.accepts(feedback, packet)
}
