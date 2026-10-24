package com.tjg.twidget.core

internal sealed class JsonValue {
    data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue() {
        fun string(name: String): String = (values[name] as? StringValue)?.value
            ?: error("Missing or invalid '$name'")
        fun optionalString(name: String): String? = when (val value = values[name]) {
            null, NullValue -> null
            is StringValue -> value.value
            else -> error("Invalid '$name'")
        }
        fun long(name: String): Long = (values[name] as? NumberValue)?.value?.toLongOrNull()
            ?: error("Missing or invalid '$name'")
        fun optionalLong(name: String): Long? = when (val value = values[name]) {
            null, NullValue -> null
            is NumberValue -> value.value.toLongOrNull() ?: error("Invalid '$name'")
            else -> error("Invalid '$name'")
        }
        fun optionalBoolean(name: String): Boolean = when (val value = values[name]) {
            null, NullValue -> false
            is BooleanValue -> value.value
            else -> error("Invalid '$name'")
        }
        fun boolean(name: String): Boolean = (values[name] as? BooleanValue)?.value
            ?: error("Missing or invalid '$name'")
        fun array(name: String): ArrayValue = values[name] as? ArrayValue
            ?: error("Missing or invalid '$name'")
        fun objectValue(name: String): ObjectValue = values[name] as? ObjectValue
            ?: error("Missing or invalid '$name'")
        fun optionalObject(name: String): ObjectValue? = values[name] as? ObjectValue
    }

    data class ArrayValue(val values: List<JsonValue>) : JsonValue()
    data class StringValue(val value: String) : JsonValue()
    data class NumberValue(val value: String) : JsonValue()
    data class BooleanValue(val value: Boolean) : JsonValue()
    data object NullValue : JsonValue()

    fun asObject(): ObjectValue = this as? ObjectValue ?: error("Expected JSON object")
    fun asArray(): ArrayValue = this as? ArrayValue ?: error("Expected JSON array")
}

internal fun json(value: String?): JsonValue =
    value?.let(JsonValue::StringValue) ?: JsonValue.NullValue

internal fun json(value: Long?): JsonValue =
    value?.let { JsonValue.NumberValue(it.toString()) } ?: JsonValue.NullValue

internal fun json(value: Int): JsonValue = JsonValue.NumberValue(value.toString())

internal fun json(value: Boolean): JsonValue = JsonValue.BooleanValue(value)

internal fun jsonObject(vararg values: Pair<String, JsonValue>) =
    JsonValue.ObjectValue(linkedMapOf(*values))

internal object JsonText {
    fun parse(text: String): JsonValue = Parser(text).parse()

    fun stringify(value: JsonValue): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.ObjectValue -> {
                append('{')
                value.values.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(entry.key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is JsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is JsonValue.StringValue -> appendQuoted(value.value)
            is JsonValue.NumberValue -> append(value.value)
            is JsonValue.BooleanValue -> append(value.value)
            JsonValue.NullValue -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            require(index == source.length) { "Unexpected trailing JSON content" }
            return value
        }

        private fun readValue(): JsonValue {
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.StringValue(readString())
                't' -> readLiteral("true", JsonValue.BooleanValue(true))
                'f' -> readLiteral("false", JsonValue.BooleanValue(false))
                'n' -> readLiteral("null", JsonValue.NullValue)
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue.ObjectValue {
            index++
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.ObjectValue(values)
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "Expected JSON object key" }
                val name = readString()
                skipWhitespace()
                require(consume(':')) { "Expected ':'" }
                values[name] = readValue()
                skipWhitespace()
                if (consume('}')) break
                require(consume(',')) { "Expected ','" }
            }
            return JsonValue.ObjectValue(values)
        }

        private fun readArray(): JsonValue.ArrayValue {
            index++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.ArrayValue(values)
            while (true) {
                values += readValue()
                skipWhitespace()
                if (consume(']')) break
                require(consume(',')) { "Expected ','" }
            }
            return JsonValue.ArrayValue(values)
        }

        private fun readString(): String {
            require(consume('"')) { "Expected string" }
            return buildString {
                while (index < source.length) {
                    val character = source[index++]
                    when (character) {
                        '"' -> return@buildString
                        '\\' -> {
                            require(index < source.length) { "Invalid escape" }
                            when (val escaped = source[index++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b')
                                'f' -> append('\u000c')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> {
                                    require(index + 4 <= source.length) { "Invalid unicode escape" }
                                    append(source.substring(index, index + 4).toInt(16).toChar())
                                    index += 4
                                }
                                else -> error("Invalid escape")
                            }
                        }
                        else -> {
                            require(character >= ' ') { "Control character in string" }
                            append(character)
                        }
                    }
                }
                error("Unterminated string")
            }
        }

        private fun readNumber(): JsonValue.NumberValue {
            val start = index
            if (peek() == '-') index++
            while (peek()?.isDigit() == true) index++
            if (peek() == '.') {
                index++
                while (peek()?.isDigit() == true) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                while (peek()?.isDigit() == true) index++
            }
            require(index > start) { "Invalid JSON value" }
            val value = source.substring(start, index)
            require(value.toDoubleOrNull() != null) { "Invalid JSON number" }
            return JsonValue.NumberValue(value)
        }

        private fun <T : JsonValue> readLiteral(literal: String, value: T): T {
            require(source.regionMatches(index, literal, 0, literal.length)) { "Invalid JSON literal" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) index++
        }

        private fun consume(character: Char): Boolean {
            if (peek() != character) return false
            index++
            return true
        }

        private fun peek(): Char? = source.getOrNull(index)
    }
}
