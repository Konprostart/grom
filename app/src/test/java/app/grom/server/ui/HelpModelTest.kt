// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The "How Grom works" content (D62).
 *
 * The section is static on purpose: it may explain what Grom is, but it must
 * never imply that anything is currently running, and it must not smuggle in an
 * address, a price or a reward.
 */
class HelpModelTest {

    @Test
    fun `the server role has four blocks, in a fixed order`() {
        // Adding a block is a product decision, not a refactor: this fails so
        // the reviewer sees it. The fourth one is the alert explanation (D65).
        val blocks = HelpModel.blocks(serverRole = true)
        assertEquals(4, blocks.size)
        assertEquals(4, blocks.map { it.headingRes }.toSet().size)
        assertEquals(4, blocks.map { it.bodyRes }.toSet().size)
        assertEquals(R.string.help_alerts_heading, blocks.last().headingRes)
    }

    @Test
    fun `a role without alerts explains only what it has`() {
        // peripheral26 neither serves nor notifies: promising the section there
        // would be the exact kind of untruth this text exists to avoid.
        val blocks = HelpModel.blocks(serverRole = false)
        assertEquals(3, blocks.size)
        assertEquals(R.string.help_remote_heading, blocks.last().headingRes)
    }

    @Test
    fun `the roles share the first three blocks in the same order`() {
        assertEquals(
            HelpModel.blocks(serverRole = false),
            HelpModel.blocks(serverRole = true).take(3)
        )
    }

    @Test
    fun `headings and bodies alternate without reusing a resource`() {
        val all = HelpModel.blocks().flatMap { listOf(it.headingRes, it.bodyRes) }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.none { it == 0 })
    }

    @Test
    fun `the section has a closing honesty line`() {
        assertTrue(HelpModel.footnoteRes() != 0)
        assertNotEquals(HelpModel.footnoteRes(), HelpModel.blocks().last().bodyRes)
    }

    @Test
    fun `every block names a string that exists`() {
        val xml = stringsXml()
        assumeTrue("strings.xml not reachable from this working dir", xml != null)
        val names = Regex("""<string name="([A-Za-z0-9_]+)"""")
            .findAll(xml!!)
            .map { it.groupValues[1] }
            .toSet()
        listOf(
            "help_what_heading", "help_what_body",
            "help_local_heading", "help_local_body",
            "help_remote_heading", "help_remote_body",
            "help_alerts_heading", "help_alerts_body",
            "help_footnote"
        ).forEach { name ->
            assertTrue("missing string: $name", names.contains(name))
        }
    }

    @Test
    fun `the help text contains no address, currency or reward claim`() {
        val xml = stringsXml()
        assumeTrue("strings.xml not reachable from this working dir", xml != null)
        val helpText = Regex("""<string name="(help_[A-Za-z0-9_]+)">(.*?)</string>""")
            .findAll(xml!!)
            .joinToString(" ") { it.groupValues[2] }
            .lowercase()
        assertTrue(helpText.isNotEmpty())
        listOf("http://", "https://", "www.", "€", "$", "usd", "eur", "balance", "payout")
            .forEach { marker ->
                assertFalse("help text must not contain '$marker'", helpText.contains(marker))
            }
        // It must not promise uptime either: the only allowed claim is that a
        // down link is shown as down.
        assertFalse(helpText.contains("always online"))
        assertFalse(helpText.contains("guaranteed"))
    }

    private fun stringsXml(): String? {
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }
}
