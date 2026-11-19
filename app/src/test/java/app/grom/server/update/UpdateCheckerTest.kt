// SPDX-License-Identifier: Apache-2.0
package app.grom.server.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val releaseJson = """
        {
          "tag_name": "v0.2.0",
          "name": "Grom 0.2.0",
          "body": "Monochrome UI and in-app update check.",
          "html_url": "https://github.com/sveatoken/grom/releases/tag/v0.2.0",
          "assets": [
            {
              "name": "grom-0.2.0-server29.apk",
              "browser_download_url": "https://example.invalid/grom-0.2.0-server29.apk"
            },
            {
              "name": "grom-0.2.0-peripheral26.apk",
              "browser_download_url": "https://example.invalid/grom-0.2.0-peripheral26.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `normalizes tags and flavour suffixes`() {
        assertEquals("0.2.0", UpdateChecker.normalizeVersion("v0.2.0"))
        assertEquals("0.2.0", UpdateChecker.normalizeVersion("0.2.0-server29"))
        assertEquals("1.4", UpdateChecker.normalizeVersion("V1.4+5"))
        assertEquals("", UpdateChecker.normalizeVersion("   "))
    }

    @Test
    fun `compares versions numerically, not lexically`() {
        assertTrue(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.10.0"))
        assertTrue(UpdateChecker.isNewer("1.9", "1.10"))
        assertTrue(UpdateChecker.isNewer("0.2", "0.2.1"))
        assertFalse(UpdateChecker.isNewer("0.2.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.2.0-server29", "0.2"))
        assertFalse(UpdateChecker.isNewer("0.3.0", "0.2.9"))
    }

    @Test
    fun `ignores unparseable versions`() {
        assertFalse(UpdateChecker.isNewer("dev", "1.0"))
        assertFalse(UpdateChecker.isNewer("0.2.0", "latest"))
        assertFalse(UpdateChecker.isNewer("1.2.3.4.5", "9.9.9.9.9"))
    }

    @Test
    fun `parses a release and selects the matching flavour asset`() {
        val server = UpdateChecker.parseRelease(releaseJson, serverRole = true)
        assertNotNull(server)
        assertEquals("v0.2.0", server!!.tag)
        assertEquals("0.2.0", server.version)
        assertEquals("Grom 0.2.0", server.title)
        assertTrue(server.notes.contains("Monochrome"))
        assertEquals("https://example.invalid/grom-0.2.0-server29.apk", server.apkUrl)

        val peripheral = UpdateChecker.parseRelease(releaseJson, serverRole = false)
        assertEquals("https://example.invalid/grom-0.2.0-peripheral26.apk", peripheral!!.apkUrl)
    }

    @Test
    fun `falls back to the first apk when the flavour asset is absent`() {
        val json = """
            {"tag_name":"v9.0.0","assets":[{"name":"grom.apk","browser_download_url":"https://x/y.apk"}]}
        """.trimIndent()
        assertEquals("https://x/y.apk", UpdateChecker.parseRelease(json, serverRole = false)!!.apkUrl)
    }

    @Test
    fun `returns null for documents without a tag`() {
        assertNull(UpdateChecker.parseRelease("{}"))
        assertNull(UpdateChecker.parseRelease("""{"tag_name":""}"""))
        assertNull(UpdateChecker.parseRelease("not json"))
    }

    @Test
    fun `check reports a newer release, current build, and failures distinctly`() {
        val newer = UpdateChecker.check("0.1.0", fetch = { releaseJson })
        assertEquals("0.2.0", newer.getOrNull()!!.version)

        val current = UpdateChecker.check("0.2.0", fetch = { releaseJson })
        assertNull(current.getOrNull())

        val failed = UpdateChecker.check("0.1.0", fetch = { throw java.io.IOException("offline") })
        assertTrue(failed.isFailure)
    }
}
