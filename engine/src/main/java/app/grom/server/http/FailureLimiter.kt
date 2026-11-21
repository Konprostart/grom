// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

/**
 * Failed-authentication limiter (S2-R015): more than 20 failures inside 60 s
 * answers 429 until the window drains. A successful authentication resets it.
 */
class FailureLimiter(
    private val maxFailures: Int = 20,
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val failures = ArrayDeque<Long>()

    fun isBlocked(): Boolean {
        prune()
        return failures.size >= maxFailures
    }

    fun noteFailure() {
        prune()
        failures.addLast(clock())
    }

    fun noteSuccess() {
        failures.clear()
    }

    fun failureCount(): Int {
        prune()
        return failures.size
    }

    private fun prune() {
        val cutoff = clock() - windowMs
        while (failures.isNotEmpty() && failures.first() < cutoff) {
            failures.removeFirst()
        }
    }
}
