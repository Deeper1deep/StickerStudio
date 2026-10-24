package com.stickerstudio.app.core

/**
 * A tiny, dependency-free JSON reader/writer.
 *
 * Why not org.json or kotlinx.serialization? This project keeps JSON handling in plain
 * Kotlin so that the exact same code runs (and is unit-tested) on a plain JVM, without
 * the Android stubs that make org.json unusable in local unit tests.
 *
 * Parsed values are: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
class JsonParseException(message: String) : Exception(message)

object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.readValue(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonParseException("Trailing data at ${p.pos}")
        return value
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    // ---------------------------------------------------------------- writer

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> sb.append(if (value.isNaN() || value.isInfinite()) "null" else value.toString())
            is Double -> sb.append(if (value.isNaN() || value.isInfinite()) "null" else value.toString())
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, v)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON type: ${value::class.java.name}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    // ---------------------------------------------------------------- reader

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > 64) throw JsonParseException("Nesting too deep")
            if (atEnd()) throw JsonParseException("Unexpected end of input")
            val c = s[pos]
            return when {
                c == '{' -> readObject(depth)
                c == '[' -> readArray(depth)
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> throw JsonParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonParseException("Bad literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw JsonParseException("Expected key at $pos")
                val key = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw JsonParseException("Expected ':' at $pos")
                pos++
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                if (atEnd()) throw JsonParseException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw JsonParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                list.add(readValue(depth + 1))
                skipWs()
                if (atEnd()) throw JsonParseException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw JsonParseException("Expected ',' or ']' at $pos")
                }
            }
        }

        fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("Bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonParseException("Bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonParseException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            val digitsStart = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == digitsStart) throw JsonParseException("Bad number at $start")
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (!isFloat) {
                val l = text.toLongOrNull()
                if (l != null) return l
            }
            return text.toDoubleOrNull() ?: throw JsonParseException("Bad number '$text'")
        }
    }
}

// ----------------------------------------------------------------- accessors

@Suppress("UNCHECKED_CAST")
fun Any?.asObject(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asArray(): List<Any?>? = this as? List<Any?>

fun Map<String, Any?>.optString(key: String): String? = this[key] as? String
fun Map<String, Any?>.optInt(key: String, default: Int): Int = (this[key] as? Number)?.toInt() ?: default
fun Map<String, Any?>.optLong(key: String, default: Long): Long = (this[key] as? Number)?.toLong() ?: default
fun Map<String, Any?>.optFloat(key: String, default: Float): Float = (this[key] as? Number)?.toFloat() ?: default
fun Map<String, Any?>.optBool(key: String, default: Boolean): Boolean = (this[key] as? Boolean) ?: default
