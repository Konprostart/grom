// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

/**
 * A minimal JSON writer (D3: no third-party JSON library).
 *
 * Only the shapes Grom emits are supported: maps, lists, strings, numbers,
 * booleans and null. Numbers are rendered without a decimal part when they are
 * integral, so clients see `"api":1` and not `"api":1.0`.
 */
object Json {

    class Raw(val literal: String)

    fun encode(value: Any?): String {
        val sb = StringBuilder(128)
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Raw -> sb.append(value.literal)
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> writeDecimal(sb, value.toDouble())
            is Double -> writeDecimal(sb, value)
            is Number -> sb.append(value.toString())
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
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, value.toList())
            is IntArray -> write(sb, value.toList())
            is LongArray -> write(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeDecimal(sb: StringBuilder, value: Double) {
        if (value.isNaN() || value.isInfinite()) {
            sb.append("null")
        } else if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            sb.append(value.toLong().toString())
        } else {
            sb.append(value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }
}
