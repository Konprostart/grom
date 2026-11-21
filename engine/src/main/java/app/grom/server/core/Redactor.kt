// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

/**
 * Secret redaction for logs and audit details (D26, S3-R029).
 */
class Redactor(secrets: Collection<String> = emptyList()) {

    private val secrets: List<String> = secrets.filter { it.isNotEmpty() }

    fun with(vararg extra: String): Redactor = Redactor(this.secrets + extra.filter { it.isNotEmpty() })

    fun redact(input: String?): String {
        val source = input ?: return ""
        var out = source
        for (secret in secrets) {
            if (secret.isNotEmpty() && out.contains(secret)) {
                out = out.replace(secret, MARKER)
            }
        }
        return BEARER.replace(out) { match -> match.groupValues[1] + MARKER }
    }

    companion object {
        const val MARKER = "\u00abredacted\u00bb"
        private val BEARER = Regex("""(Bearer\s+)\S+""", RegexOption.IGNORE_CASE)
    }
}
