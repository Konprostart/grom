// SPDX-License-Identifier: Apache-2.0
package app.grom.server.projects

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectRegistryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun registry(clock: () -> Long = { 1_000L }) =
        ProjectRegistry(temp.root.resolve("projects.json"), clock)

    private fun draft(id: String = "my-wallet", title: String = "My Wallet") = mapOf<String, Any?>(
        "id" to id,
        "title" to title,
        "version" to "1.2.0",
        "package" to "com.example.wallet",
        "capabilities" to listOf("kv", "lightning.ref")
    )

    @Test
    fun `announce stores a record and reports it`() {
        val registry = registry()
        val record = registry.announce(draft())
        assertEquals("my-wallet", record.id)
        assertEquals("My Wallet", record.title)
        assertEquals(listOf("kv", "lightning.ref"), record.capabilities)

        val list = registry.summary()
        assertEquals(1, list.size)
        assertEquals("alive", list[0]["state"])
    }

    @Test
    fun `heartbeat refreshes lastSeen but keeps registeredAt`() {
        var now = 1_000L
        val registry = registry { now }
        val first = registry.announce(draft())
        now = 40_000L
        val second = registry.announce(draft())
        assertEquals(first.registeredAtMs, second.registeredAtMs)
        assertEquals(40_000L, second.lastSeenMs)
    }

    @Test
    fun `state is derived from the heartbeat, never set by the caller`() {
        var now = 1_000L
        val registry = registry { now }
        // A caller that sends "state":"alive" must not be believed.
        val record = registry.announce(draft() + ("state" to "alive"))
        assertEquals("alive", record.state(now))

        now = 1_000L + ProjectRecord.STALE_AFTER_MS + 1
        assertEquals("stale", record.state(now))
        assertEquals(
            "stale",
            registry.summary().single()["state"]
        )
        assertFalse(record.state(now) == "alive")
    }

    @Test
    fun `invalid ids are refused`() {
        for (bad in listOf("My-Wallet", "a", "../etc", "x".repeat(40), "", "with space")) {
            val failure = runCatching { registry().announce(draft(id = bad)) }.exceptionOrNull()
            assertTrue(
                "expected BAD_PROJECT for '$bad', got $failure",
                failure is ApiException && failure.code == "BAD_PROJECT"
            )
        }
    }

    @Test
    fun `titles and capabilities are bounded`() {
        val longTitle = runCatching {
            registry().announce(draft(title = "t".repeat(65)))
        }.exceptionOrNull()
        assertTrue(longTitle is ApiException)

        val manyCapabilities = runCatching {
            registry().announce(
                draft() + ("capabilities" to List(13) { "cap$it" })
            )
        }.exceptionOrNull()
        assertTrue(manyCapabilities is ApiException)

        val badCapability = runCatching {
            registry().announce(draft() + ("capabilities" to listOf("BAD CAP")))
        }.exceptionOrNull()
        assertTrue(badCapability is ApiException)
    }

    @Test
    fun `the registry is capped`() {
        val registry = registry()
        for (index in 1..ProjectRegistry.MAX_PROJECTS) {
            registry.announce(draft(id = "project-$index"))
        }
        val failure = runCatching {
            registry.announce(draft(id = "one-too-many"))
        }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.code == "TOO_MANY_PROJECTS")
    }

    @Test
    fun `remove deletes and an unknown id is reported`() {
        val registry = registry()
        registry.announce(draft())
        registry.remove("my-wallet")
        assertTrue(registry.list().isEmpty())

        val failure = runCatching { registry.remove("my-wallet") }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.code == "PROJECT_NOT_FOUND")
    }

    @Test
    fun `the file contains no secret and survives a reload`() {
        val file = temp.root.resolve("projects.json")
        val registry = ProjectRegistry(file)
        registry.announce(draft())

        val text = file.readText()
        assertFalse(text.contains("password"))
        assertFalse(text.contains("token"))
        assertFalse(text.contains("nsec"))

        val parsed = JsonParser.parseObjectOrNull(text)
        assertNotNull(parsed)
        assertEquals(1, (parsed!!["projects"] as? List<*>)?.size)

        // A fresh instance reads the same record.
        assertEquals("my-wallet", ProjectRegistry(file).list().single().id)
    }

    @Test
    fun `a corrupt file degrades to empty with a reported reason`() {
        val file = temp.root.resolve("projects.json")
        file.writeText("{not json")
        val registry = ProjectRegistry(file)
        assertTrue(registry.list().isEmpty())
        assertNotNull(registry.lastError)
    }

    @Test
    fun `the JSON shape is exactly the documented one`() {
        val registry = registry()
        registry.announce(draft())
        val json = Json.encode(mapOf("projects" to registry.summary()))
        val parsed = JsonParser.parseObjectOrNull(json)!!
        @Suppress("UNCHECKED_CAST")
        val project = (parsed["projects"] as List<*>).single() as Map<String, Any?>
        assertEquals("my-wallet", project["id"])
        assertEquals("My Wallet", project["title"])
        assertEquals("com.example.wallet", project["package"])
        assertEquals("alive", project["state"])
        assertNull(project["secret"])
    }
}
