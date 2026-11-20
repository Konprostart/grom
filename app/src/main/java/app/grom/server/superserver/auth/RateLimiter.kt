// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import java.util.LinkedHashMap
import kotlin.math.ceil

/**
 * Per-client request budget (D42, S5-R016).
 *
 * Each client (an IP string, never a credential) owns a token bucket with
 * [capacity] burst tokens refilled at [refillPerSecond]. Clients are tracked in
 * an access-ordered map bounded by [maxClients]; the least recently used client
 * is evicted when the bound is exceeded, so hostile traffic cannot grow memory
 * without limit. All access is synchronized.
 */
class RateLimiter(
    private val capacity: Int = 60,
    private val refillPerSecond: Double = 2.0,
    private val maxClients: Int = 1024,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class Decision(val allowed: Boolean, val retryAfterSeconds: Int)

    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val clients = LinkedHashMap<String, Bucket>(16, 0.75f, true)

    @Synchronized
    fun check(client: String): Decision {
        val now = clock()
        val bucket = clients.getOrPut(client) { Bucket(capacity.toDouble(), now) }

        // Refill only when the wall clock moved forward. If it jumped backwards
        // we keep the last timestamp, so no tokens are manufactured by a clock
        // change and the bucket resumes normally once time catches up.
        if (now > bucket.lastRefillMs) {
            if (refillPerSecond > 0.0) {
                val elapsedSeconds = (now - bucket.lastRefillMs) / 1000.0
                bucket.tokens = (bucket.tokens + elapsedSeconds * refillPerSecond)
                    .coerceAtMost(capacity.toDouble())
            }
            bucket.lastRefillMs = now
        }

        val decision = if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            Decision(true, 0)
        } else {
            Decision(false, retryAfter(bucket.tokens))
        }

        evict()
        return decision
    }

    @Synchronized
    fun size(): Int = clients.size

    private fun retryAfter(tokens: Double): Int {
        val seconds = ceil((1.0 - tokens) / refillPerSecond)
        return when {
            seconds.isNaN() -> MAX_RETRY_AFTER_SECONDS
            seconds <= 1.0 -> 1
            seconds >= MAX_RETRY_AFTER_SECONDS.toDouble() -> MAX_RETRY_AFTER_SECONDS
            else -> seconds.toInt().coerceIn(1, MAX_RETRY_AFTER_SECONDS)
        }
    }

    private fun evict() {
        while (clients.size > maxClients) {
            val iterator = clients.keys.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val MAX_RETRY_AFTER_SECONDS: Int = 3600
    }
}

/**
 * Failed-password limiter for the login endpoint (D42, S5-R015).
 *
 * A client that accumulates [maxFailures] failures inside [windowMs] is locked
 * out for [lockoutMs]. A successful login clears the client's history. As with
 * [RateLimiter], the client key is an IP string and the map is access-ordered
 * and bounded by [maxClients].
 */
class LoginLimiter(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val lockoutMs: Long = 300_000,
    private val maxClients: Int = 1024,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class Status(val locked: Boolean, val retryAfterSeconds: Int)

    private class Track {
        val failures = ArrayDeque<Long>()
        var lockoutUntilMs: Long = 0L
    }

    private val clients = LinkedHashMap<String, Track>(16, 0.75f, true)

    @Synchronized
    fun check(client: String): Status {
        val now = clock()
        val track = clients[client] ?: return Status(false, 0)
        if (track.lockoutUntilMs != 0L && track.lockoutUntilMs > now) {
            return Status(true, retryAfter(track.lockoutUntilMs - now))
        }
        if (track.lockoutUntilMs != 0L) track.lockoutUntilMs = 0L
        prune(track, now)
        return Status(false, 0)
    }

    @Synchronized
    fun noteFailure(client: String): Status {
        val now = clock()
        val track = clients.getOrPut(client) { Track() }

        // A failure recorded while the lockout is still active does not extend
        // it; once the lockout has expired the counter starts over.
        if (track.lockoutUntilMs == 0L || track.lockoutUntilMs <= now) {
            track.lockoutUntilMs = 0L
            prune(track, now)
            track.failures.addLast(now)
            if (track.failures.size >= maxFailures) {
                track.failures.clear()
                track.lockoutUntilMs = now + lockoutMs
            }
        }

        evict()
        return check(client)
    }

    @Synchronized
    fun noteSuccess(client: String) {
        clients.remove(client)
    }

    private fun prune(track: Track, now: Long) {
        val cutoff = now - windowMs
        while (track.failures.isNotEmpty() && track.failures.first() < cutoff) {
            track.failures.removeFirst()
        }
    }

    private fun retryAfter(remainingMs: Long): Int {
        val seconds = ceil(remainingMs / 1000.0)
        return when {
            seconds <= 1.0 -> 1
            seconds >= MAX_RETRY_AFTER_SECONDS.toDouble() -> MAX_RETRY_AFTER_SECONDS
            else -> seconds.toInt().coerceIn(1, MAX_RETRY_AFTER_SECONDS)
        }
    }

    private fun evict() {
        while (clients.size > maxClients) {
            val iterator = clients.keys.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val MAX_RETRY_AFTER_SECONDS: Int = 86_400
    }
}
