// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

/**
 * A minimal JSON reader (D3: no third-party JSON library).
 *
 * [Json] writes the shapes Grom emits; this reads the shapes Grom *consumes* —
 * today that is the GitHub release document used by the update checker, and the
 * status document the server returns over Binder. Both are small, trusted
 * documents, so a strict recursive-descent parser is enough.
 *
 * Numbers become [Long] when they are integral and fit, otherwise [Double].
 * Objects become [LinkedHashMap], arrays [List]. Malformed input throws
 * [IllegalArgumentException]; callers that must not crash wrap the call.
 */
object JsonParser {

    fun parse(text: String): Any? {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.readValue()
        cursor.skipWhitespace()
        if (!cursor.atEnd()) {
            throw IllegalArgumentException("trailing data at offset ${cursor.offset}")
        }
        return value
    }

    /** Parses an object document, or returns null when [text] is not one. */
    @Suppress("UNCHECKED_CAST")
    fun parseObjectOrNull(text: String): Map<String, Any?>? =
        runCatching { parse(text) as? Map<String, Any?> }.getOrNull()

    private class Cursor(private val text: String) {

        var offset: Int = 0
            private set

        fun atEnd(): Boolean = offset >= text.length

        fun skipWhitespace() {
            while (!atEnd() && text[offset].isWhitespace()) offset++
        }

        fun readValue(): Any? {
            if (atEnd()) fail("unexpected end of document")
            return when (val c = text[offset]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected '$c'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                offset++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a string key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> offset++
                    '}' -> {
                        offset++
                        return map
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                offset++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> offset++
                    ']' -> {
                        offset++
                        return list
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = text[offset++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val escape = text[offset++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (offset + 4 > text.length) fail("truncated \\u escape")
                                val hex = text.substring(offset, offset + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar())
                                offset += 4
                            }
                            else -> fail("unknown escape '\\$escape'")
                        }
                    }
                    else -> {
                        if (c < ' ') fail("control character in string")
                        sb.append(c)
                    }
                }
            }
        }

        private fun readNumber(): Any {
            val start = offset
            if (peek() == '-') offset++
            while (!atEnd() && text[offset].isDigit()) offset++
            var integral = true
            if (!atEnd() && text[offset] == '.') {
                integral = false
                offset++
                while (!atEnd() && text[offset].isDigit()) offset++
            }
            if (!atEnd() && (text[offset] == 'e' || text[offset] == 'E')) {
                integral = false
                offset++
                if (!atEnd() && (text[offset] == '+' || text[offset] == '-')) offset++
                while (!atEnd() && text[offset].isDigit()) offset++
            }
            val literal = text.substring(start, offset)
            if (literal == "-") fail("bad number")
            if (integral) {
                literal.toLongOrNull()?.let { return it }
            }
            return literal.toDoubleOrNull() ?: fail("bad number '$literal'")
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, offset)) fail("expected '$literal'")
            offset += literal.length
            return value
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("expected '$c'")
            offset++
        }

        private fun peek(): Char =
            if (atEnd()) fail("unexpected end of document") else text[offset]

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("$message at offset $offset")
    }
}
