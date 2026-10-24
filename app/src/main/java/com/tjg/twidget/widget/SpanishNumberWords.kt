package com.tjg.twidget.widget

/** Masculine cardinal form, used immediately before seguidores or a scale noun. */
internal object SpanishNumberWords {
    fun spell(value: Long): String {
        require(value >= 0)
        val words = cardinal(value)
        return words.replaceFirstChar { it.uppercase() }
    }

    private fun cardinal(value: Long): String {
        val small = listOf("cero", "un", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
            "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve",
            "veinte", "veintiún", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve")
        if (value < 30) return small[value.toInt()]
        if (value < 100) {
            val tens = listOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
            return tens[(value / 10).toInt()] + if (value % 10 == 0L) "" else " y " + cardinal(value % 10)
        }
        if (value == 100L) return "cien"
        if (value < 1000) {
            val hundreds = listOf("", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos")
            return hundreds[(value / 100).toInt()] + if (value % 100 == 0L) "" else " " + cardinal(value % 100)
        }
        if (value < 1_000_000) return (if (value < 2000) "mil" else cardinal(value / 1000) + " mil") +
            if (value % 1000 == 0L) "" else " " + cardinal(value % 1000)
        val (scale, singular, plural) = listOf(
            Triple(1_000_000_000_000_000_000L, "trillón", "trillones"),
            Triple(1_000_000_000_000L, "billón", "billones"),
            Triple(1_000_000L, "millón", "millones"),
        ).first { value >= it.first }
        val count = value / scale
        return (if (count == 1L) "un $singular" else cardinal(count) + " " + plural) +
            if (value % scale == 0L) "" else " " + cardinal(value % scale)
    }
}
