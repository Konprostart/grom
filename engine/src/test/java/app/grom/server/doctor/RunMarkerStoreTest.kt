// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The run marker: the only remembered fact in the Doctor (D83).
 *
 * The tests are about the difference between "we saw it die" and "we do not
 * know". An absent file, a corrupt file and a file the store declined to read
 * are all *absent knowledge*, and must not be counted as an unexpected stop —
 * that would be inventing a failure.
 */
class RunMarkerStoreTest {

    private lateinit var dir: File
    private lateinit var store: RunMarkerStore
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "grom-doctor-${System.nanoTime()}")
        dir.mkdirs()
        file = File(dir, RunMarkerStore.FILE_NAME)
        store = RunMarkerStore(file)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a missing marker reports nothing, not a failure`() {
        val marker = store.current()
        assertEquals(0L, marker.sessionStartedAtMs)
        assertNull(marker.cleanStop)
        assertFalse(marker.diedUncleanly())
        assertEquals(0, marker.unexpectedStops)
    }

    @Test
    fun `the first start has no predecessor to blame`() {
        val previous = store.beginSession(1_000L)
        assertFalse(previous.diedUncleanly())
        assertEquals(0, previous.unexpectedStops)

        val written = store.current()
        assertEquals(1_000L, written.sessionStartedAtMs)
        assertEquals(false, written.cleanStop)
    }

    @Test
    fun `a clean stop keeps the counter still`() {
        store.beginSession(1_000L)
        store.endSessionCleanly(2_000L)
        assertEquals(true, store.current().cleanStop)
        assertEquals(2_000L, store.current().lastCleanStopMs)

        val previous = store.beginSession(3_000L)
        assertFalse(previous.diedUncleanly())
        assertEquals(0, store.current().unexpectedStops)
    }

    @Test
    fun `an unclean end is recorded once, on the next start`() {
        store.beginSession(1_000L)
        // No endSessionCleanly: the process died.
        val previous = store.beginSession(5_000L)
        assertTrue(previous.diedUncleanly())

        val now = store.current()
        assertEquals(1, now.unexpectedStops)
        // Detection time, not an invented death time.
        assertEquals(5_000L, now.lastUnexpectedStopMs)
        assertEquals(1_000L, now.previousSessionStartedAtMs)
        assertEquals(false, now.cleanStop)
    }

    @Test
    fun `repeated unclean ends accumulate and keep the last one`() {
        store.beginSession(1_000L)
        store.beginSession(2_000L)
        store.beginSession(3_000L)
        val now = store.current()
        assertEquals(2, now.unexpectedStops)
        assertEquals(3_000L, now.lastUnexpectedStopMs)
        assertEquals(2_000L, now.previousSessionStartedAtMs)
    }

    @Test
    fun `two full cycles survive a reload from disk`() {
        store.beginSession(1_000L)
        store.endSessionCleanly(1_500L)
        store.beginSession(2_000L)
        store.endSessionCleanly(2_500L)
        store.beginSession(3_000L)

        // A fresh store over the same file is what a new process sees.
        val reloaded = RunMarkerStore(file)
        val marker = reloaded.current()
        assertEquals(3_000L, marker.sessionStartedAtMs)
        assertEquals(false, marker.cleanStop)
        assertEquals(0, marker.unexpectedStops)
        assertEquals(2_500L, marker.lastCleanStopMs)
        assertNull(reloaded.lastError)
    }

    @Test
    fun `an unclean end survives a reload`() {
        store.beginSession(1_000L)
        val reloaded = RunMarkerStore(file)
        assertTrue(reloaded.current().diedUncleanly())
    }

    @Test
    fun `a corrupt file is ignored, not deleted and not counted`() {
        file.writeText("{ this is not json")
        val marker = store.current()
        assertEquals(0L, marker.sessionStartedAtMs)
        assertFalse(marker.diedUncleanly())
        assertEquals(0, marker.unexpectedStops)
        assertNotNull("the reason should be recorded", store.lastError)
        assertTrue("the file must not be deleted", file.exists())
    }

    @Test
    fun `an oversized file is refused`() {
        file.writeText("x".repeat(RunMarkerStore.MAX_FILE_BYTES + 1))
        val marker = store.current()
        assertEquals(0, marker.unexpectedStops)
        assertNotNull(store.lastError)
    }

    @Test
    fun `a negative or absurd counter is clamped`() {
        file.writeText("""{"version":1,"sessionStartedAtMs":10,"cleanStop":true,"unexpectedStops":-5}""")
        assertEquals(0, RunMarkerStore(file).current().unexpectedStops)

        // A fresh store, because `current()` caches what it read: rewriting the
        // file underneath a live store is not a scenario a process can hit.
        file.writeText("""{"version":1,"sessionStartedAtMs":10,"cleanStop":true,"unexpectedStops":999999}""")
        assertEquals(
            RunMarkerStore.MAX_UNEXPECTED_STOPS,
            RunMarkerStore(file).current().unexpectedStops
        )
    }

    @Test
    fun `a clean stop with no session writes nothing`() {
        // Erasing the file here would destroy the evidence that a begin was
        // skipped; the store leaves it alone.
        store.endSessionCleanly(1_000L)
        assertFalse(file.exists())
    }

    @Test
    fun `the file stays small and readable`() {
        store.beginSession(1_000L)
        store.endSessionCleanly(2_000L)
        assertTrue(file.length() < RunMarkerStore.MAX_FILE_BYTES)
        val text = file.readText()
        assertTrue(text.contains("cleanStop"))
        assertFalse("no free text is stored", text.contains("cause"))
    }

    @Test
    fun `clear resets the counter without deleting the file`() {
        store.beginSession(1_000L)
        store.beginSession(2_000L)
        assertEquals(1, store.current().unexpectedStops)
        store.clear()
        assertEquals(0, store.current().unexpectedStops)
        assertTrue(file.exists())
    }
}
