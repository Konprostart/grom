// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScopedStorageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(file: java.io.File = temp.newFile("scoped-storage.json")): ScopedStorageStore =
        ScopedStorageStore(file)

    @Test
    fun putThenGetRoundTripsWithinOneNamespace() {
        val store = store()
        store.put("peer:aaaa", "theme", "dark")
        assertEquals("dark", store.get("peer:aaaa", "theme"))
    }

    @Test
    fun namespacesAreIsolated() {
        val store = store()
        store.put("peer:aaaa", "k", "value-a")
        store.put("peer:bbbb", "k", "value-b")

        assertEquals("value-a", store.get("peer:aaaa", "k"))
        assertEquals("value-b", store.get("peer:bbbb", "k"))
        // No cross-namespace read, and a foreign key is invisible.
        assertNull(store.get("peer:aaaa", "does-not-exist"))
        assertEquals(listOf("k"), store.list("peer:aaaa"))
        assertEquals(listOf("k"), store.list("peer:bbbb"))
        assertTrue(store.list("peer:cccc").isEmpty())
    }

    @Test
    fun deleteRemovesOnlyTheNamedKey() {
        val store = store()
        store.put("peer:aaaa", "a", "1")
        store.put("peer:aaaa", "b", "2")

        assertTrue(store.delete("peer:aaaa", "a"))
        assertNull(store.get("peer:aaaa", "a"))
        assertEquals("2", store.get("peer:aaaa", "b"))
        assertFalse(store.delete("peer:aaaa", "missing"))
    }

    @Test
    fun listIsSorted() {
        val store = store()
        store.put("peer:aaaa", "zeta", "1")
        store.put("peer:aaaa", "alpha", "2")
        store.put("peer:aaaa", "mid", "3")
        assertEquals(listOf("alpha", "mid", "zeta"), store.list("peer:aaaa"))
    }

    @Test
    fun dataSurvivesAcrossStoreInstancesOnTheSameFile() {
        val file = temp.newFile("persist.json")
        store(file).put("peer:aaaa", "k", "survivor")

        // A fresh store instance re-opens the same file: the value must still be
        // there, which is what a Grom process restart observes.
        assertEquals("survivor", store(file).get("peer:aaaa", "k"))
    }

    @Test
    fun invalidKeysAndValuesAreRejected() {
        val store = store()
        for (bad in listOf("", "with space", "with/slash", "é", "a".repeat(129))) {
            try {
                store.put("peer:aaaa", bad, "v")
                throw AssertionError("expected an exception for key: '$bad'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        try {
            store.put("peer:aaaa", "k", "x".repeat(ScopedStorageStore.MAX_VALUE_BYTES + 1))
            throw AssertionError("expected an exception for an oversized value")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aCorruptFileIsReportedAndTreatedAsEmpty() {
        val file = temp.newFile("corrupt.json")
        file.writeText("{not json")
        val store = store(file)

        assertTrue(store.list("peer:aaaa").isEmpty())
        assertTrue(store.lastError != null)
        // A later write recovers the store.
        store.put("peer:aaaa", "k", "v")
        assertEquals("v", store.get("peer:aaaa", "k"))
    }

    @Test
    fun anOversizedFileIsRefusedWithoutBeingRead() {
        val file = temp.newFile("huge.json")
        file.writeText("x".repeat(ScopedStorageStore.MAX_FILE_BYTES + 1))
        val store = store(file)

        assertTrue(store.list("peer:aaaa").isEmpty())
        assertTrue(store.lastError!!.contains("larger than"))
    }

    @Test
    fun summaryReportsCountsAndNeverAnotherNamespacesKeys() {
        val store = store()
        store.put("peer:aaaa", "k", "v")
        val summary = store.summary()

        assertEquals(1, summary["namespaces"])
        assertEquals(1, summary["keys"])
        // The summary is a measurement, not a data dump.
        assertFalse(summary.containsKey("items"))
        assertFalse(summary.containsKey("peer:aaaa"))
    }
}
