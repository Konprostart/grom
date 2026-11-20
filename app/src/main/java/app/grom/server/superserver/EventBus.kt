// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.http.Json
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * A bounded in-process event fan-out for the SSE endpoint (D46).
 *
 * Every subscriber owns a small bounded queue. Publishing never blocks and never
 * allocates unboundedly: when a slow client's queue is full the event is dropped
 * for that client and the drop is reported in the next status snapshot. A
 * monitoring stream on a phone must never be able to grow the server's heap.
 */
class EventBus(
    private val maxSubscribers: Int = 8,
    private val queueCapacity: Int = 32
) {

    class Subscription internal constructor(
        val id: Long,
        internal val queue: ArrayBlockingQueue<String>
    ) {
        /** Test-facing bounded read; the router uses the queue directly. */
        fun poll(timeoutMs: Long): String? = queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private val subscribers = LinkedHashMap<Long, Subscription>()
    private val nextId = AtomicLong(1)
    private val dropped = AtomicLong(0)

    @Synchronized
    fun subscribe(): Subscription? {
        if (subscribers.size >= maxSubscribers) return null
        val subscription = Subscription(nextId.getAndIncrement(), ArrayBlockingQueue(queueCapacity))
        subscribers[subscription.id] = subscription
        return subscription
    }

    @Synchronized
    fun unsubscribe(subscription: Subscription) {
        subscribers.remove(subscription.id)
    }

    fun publish(type: String, data: Map<String, Any?>) {
        val frame = frame(type, data)
        val targets = synchronized(this) { subscribers.values.toList() }
        for (subscription in targets) {
            // offer() drops instead of blocking; a stalled reader must not stall
            // the server that produces the events.
            if (!subscription.queue.offer(frame)) dropped.incrementAndGet()
        }
    }

    @Synchronized
    fun subscriberCount(): Int = subscribers.size

    fun droppedCount(): Long = dropped.get()

    companion object {

        const val HEARTBEAT: String = ": ping\n\n"

        fun frame(type: String, data: Map<String, Any?>): String {
            val safeType = type.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
                .ifEmpty { "message" }
            return "event: $safeType\ndata: ${Json.encode(data)}\n\n"
        }
    }
}
