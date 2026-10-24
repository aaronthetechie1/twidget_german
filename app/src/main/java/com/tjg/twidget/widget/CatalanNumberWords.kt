package com.tjg.twidget.widget

/** Masculine cardinals before seguidors; compound hyphens follow the Catalan D-U-C rule. */
internal object CatalanNumberWords {
    fun spell(value: Long): String {
        require(value >= 0)
        return cardinal(value).replaceFirstChar { it.uppercase() }
    }

    private fun cardinal(value: Long): String {
        val small = listOf("zero", "un", "dos", "tres", "quatre", "cinc", "sis", "set", "vuit", "nou",
            "deu", "onze", "dotze", "tretze", "catorze", "quinze", "setze", "disset", "divuit", "dinou")
        if (value < 20) return small[value.toInt()]
        if (value < 100) {
            val tens = listOf("", "", "vint", "trenta", "quaranta", "cinquanta", "seixanta", "setanta", "vuitanta", "noranta")
            return tens[(value / 10).toInt()] + if (value % 10 == 0L) "" else
                (if (value < 30) "-i-" else "-") + cardinal(value % 10)
        }
        if (value < 1000) return (if (value < 200) "cent" else cardinal(value / 100) + "-cents") +
            if (value % 100 == 0L) "" else " " + cardinal(value % 100)
        if (value < 1_000_000) return (if (value < 2000) "mil" else cardinal(value / 1000) + " mil") +
            if (value % 1000 == 0L) "" else " " + cardinal(value % 1000)
        val (scale, singular, plural) = listOf(
            Triple(1_000_000_000_000_000_000L, "trilió", "trilions"),
            Triple(1_000_000_000_000L, "bilió", "bilions"),
            Triple(1_000_000L, "milió", "milions"),
        ).first { value >= it.first }
        val count = value / scale
        return (if (count == 1L) "un $singular" else cardinal(count) + " " + plural) +
            if (value % scale == 0L) "" else " " + cardinal(value % scale)
    }
}
