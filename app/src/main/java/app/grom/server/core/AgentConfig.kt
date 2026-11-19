// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

/**
 * The configuration handed to the agent over stdin (D11, S3-R007, S3-R008).
 *
 * This object is the only place that knows the wire form. It deliberately does
 * not depend on org.json so it is testable on the JVM (D30).
 */
object AgentConfig {

    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024

    /**
     * One JSON object followed by a newline. The caller writes it to the child's
     * stdin and closes the pipe (S3-R007).
     */
    fun encode(token: String, port: Int, logLevel: String): String {
        val json = StringBuilder(128)
        json.append('{')
        json.append("\"token\":\"").append(escape(token)).append("\",")
        json.append("\"port\":").append(port).append(',')
        json.append("\"logLevel\":\"").append(escape(logLevel)).append("\"")
        json.append("}\n")
        val payload = json.toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "agent config payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        return payload
    }

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
