// SPDX-License-Identifier: Apache-2.0
package app.grom.server.projects

import app.grom.server.http.ApiException
import app.grom.server.http.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNamespaceTest {

    private fun failure(block: () -> Unit): ApiException {
        try {
            block()
        } catch (api: ApiException) {
            return api
        }
        throw AssertionError("expected an ApiException")
    }

    @Test
    fun `a bare key is stored under the project prefix`() {
        assertEquals("p:my-wallet:theme", ProjectNamespace.scope("my-wallet", "theme"))
    }

    @Test
    fun `reading back an already-scoped own key is idempotent`() {
        assertEquals(
            "p:my-wallet:theme",
            ProjectNamespace.scope("my-wallet", "p:my-wallet:theme")
        )
    }

    @Test
    fun `another project's scoped key is refused`() {
        val refused = failure {
            ProjectNamespace.scope("my-wallet", "p:other-app:theme")
        }
        assertEquals("PROJECT_KEY_INVALID", refused.code)
    }

    @Test
    fun `an invalid project id is refused before any key is built`() {
        listOf("Wallet", "my wallet", "../etc", "", "a", "x".repeat(40)).forEach { id ->
            assertEquals("BAD_PROJECT", failure { ProjectNamespace.scope(id, "theme") }.code)
        }
    }

    @Test
    fun `path-like and control-character keys are refused`() {
        listOf("", " ", "a/b", "../x", "..", "a..b", "a b", "a\nb", "x".repeat(129)).forEach { key ->
            val refused = failure { ProjectNamespace.scope("my-wallet", key) }
            assertEquals("PROJECT_KEY_INVALID", refused.code)
        }
    }

    @Test
    fun `the longest valid key is accepted`() {
        val key = "k".repeat(ProjectNamespace.MAX_KEY_CHARS)
        assertEquals("p:my-wallet:$key", ProjectNamespace.scope("my-wallet", key))
    }

    @Test
    fun `the prefix is computed, never taken from the request`() {
        // Even a caller that names itself cannot reach outside its own prefix.
        val scoped = ProjectNamespace.scope("my-wallet", "token")
        assertTrue(scoped.startsWith("p:my-wallet:"))
        assertFalse(scoped.startsWith("p:other:"))
    }

    @Test
    fun `ownership is a pure prefix question`() {
        assertTrue(ProjectNamespace.owns("my-wallet", "p:my-wallet:theme"))
        assertFalse(ProjectNamespace.owns("my-wallet", "p:other:theme"))
        assertFalse(ProjectNamespace.owns("my-wallet", "theme"))
    }

    @Test
    fun `only key-value ops are reachable from a project`() {
        assertEquals(setOf("kv.get", "kv.put"), ProjectNamespace.ALLOWED_OPS)
    }

    @Test
    fun `the request body carries the scoped key, not the one supplied`() {
        val body = ProjectNamespace.buildRequest(
            "my-wallet",
            "kv.get",
            """{"key":"theme"}"""
        )
        val parsed = JsonParser.parseObjectOrNull(body)!!
        assertEquals("kv.get", parsed["op"])
        val args = parsed["args"] as Map<*, *>
        assertEquals("p:my-wallet:theme", args["key"])
        assertFalse(args.containsKey("value"))
    }

    @Test
    fun `a put carries its value`() {
        val body = ProjectNamespace.buildRequest(
            "my-wallet",
            "kv.put",
            """{"key":"theme","value":"dark"}"""
        )
        val args = (JsonParser.parseObjectOrNull(body)!!)["args"] as Map<*, *>
        assertEquals("dark", args["value"])
    }

    @Test
    fun `a foreign prefixed key cannot be smuggled through the body`() {
        val refused = failure {
            ProjectNamespace.buildRequest(
                "my-wallet",
                "kv.put",
                """{"key":"p:other:theme","value":"dark"}"""
            )
        }
        assertEquals("PROJECT_KEY_INVALID", refused.code)
    }

    @Test
    fun `only key-value ops produce a body`() {
        listOf("status", "ping", "kv.delete", "exec").forEach { op ->
            val refused = failure {
                ProjectNamespace.buildRequest("my-wallet", op, """{"key":"k"}""")
            }
            assertEquals("UNKNOWN_OP", refused.code)
        }
    }

    @Test
    fun `missing or non-object args are refused`() {
        listOf(null, "", "[]", """{"value":"x"}""", """{"key":7}""").forEach { args ->
            assertEquals(
                "BAD_ARGS",
                failure { ProjectNamespace.buildRequest("my-wallet", "kv.get", args) }.code
            )
        }
        assertEquals(
            "BAD_ARGS",
            failure {
                ProjectNamespace.buildRequest("my-wallet", "kv.put", """{"key":"k"}""")
            }.code
        )
    }

    @Test
    fun `an oversized value is refused before it reaches the agent`() {
        val body = """{"key":"k","value":"${"v".repeat(ProjectNamespace.MAX_VALUE_CHARS)}"}"""
        // Exactly at the cap is allowed.
        ProjectNamespace.buildRequest("my-wallet", "kv.put", body)
        val tooBig = """{"key":"k","value":"${"v".repeat(ProjectNamespace.MAX_VALUE_CHARS + 1)}"}"""
        assertEquals(
            "VALUE_TOO_LARGE",
            failure { ProjectNamespace.buildRequest("my-wallet", "kv.put", tooBig) }.code
        )
    }

    @Test
    fun `an invalid project id is refused before the body is built`() {
        assertEquals(
            "BAD_PROJECT",
            failure {
                ProjectNamespace.buildRequest("Bad Id", "kv.get", """{"key":"k"}""")
            }.code
        )
    }
}
