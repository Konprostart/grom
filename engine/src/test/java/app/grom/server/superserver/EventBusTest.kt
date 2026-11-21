// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventBusTest {

    @Test
    fun publishDeliversAnEncodedFrame() {
        val bus = EventBus()
        val subscription = bus.subscribe()
        assertNotNull(subscription)

        bus.publish("server", mapOf("state" to "started", "address" to "127.0.0.1:47002"))
        val frame = subscription!!.poll(1_000)
        assertNotNull(frame)
        assertTrue(frame!!.startsWith("event: server\n"))
        assertTrue(frame.contains("data: {"))
        assertTrue(frame.contains("\"state\":\"started\""))
        assertTrue(frame.endsWith("\n\n"))
    }

    @Test
    fun aFullQueueDropsInsteadOfBlocking() {
        val bus = EventBus(maxSubscribers = 1, queueCapacity = 1)
        bus.subscribe()

        bus.publish("a", mapOf("n" to 1))
        bus.publish("b", mapOf("n" to 2))
        bus.publish("c", mapOf("n" to 3))

        assertEquals(2L, bus.droppedCount())
    }

    @Test
    fun subscribersAreCappedAndRemovable() {
        val bus = EventBus(maxSubscribers = 1)
        val first = bus.subscribe()
        assertNotNull(first)
        assertNull(bus.subscribe())
        assertEquals(1, bus.subscriberCount())

        bus.unsubscribe(first!!)
        assertEquals(0, bus.subscriberCount())
        assertNotNull(bus.subscribe())
    }

    @Test
    fun eventTypeIsSanitised() {
        val frame = EventBus.frame("bad\nname: evil", mapOf("x" to 1))
        assertTrue(frame.startsWith("event: badnameevil\n"))
        assertTrue(frame.contains("data: {\"x\":1}"))
    }

    @Test
    fun heartbeatIsACommentLine() {
        assertEquals(": ping\n\n", EventBus.HEARTBEAT)
    }
}
