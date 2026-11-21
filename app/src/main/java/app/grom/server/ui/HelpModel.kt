// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R

/**
 * The "How Grom works" section (D62).
 *
 * The content is static, fixed and ordered: there is nothing here that depends
 * on a status document, so the text can never claim that something is running.
 * It is kept out of the activity so the promises it makes are reviewable in one
 * place and testable on the JVM — [HelpModelTest] asserts the block count, the
 * order and that no block smuggles in an address, a price or a balance.
 *
 * Four answers an ordinary user needs (D65 added the fourth):
 *   1. what Grom does at all;
 *   2. why most people need no external server;
 *   3. when an external SSH server helps, and what it is for;
 *   4. how the Telegram alerts work — only where they exist. A `peripheral26`
 *      build has no server and no alerts, so promising them there would be the
 *      exact kind of lie this section exists to avoid.
 */
data class HelpBlock(val headingRes: Int, val bodyRes: Int)

object HelpModel {

    /** Three blocks on a role without a server, four with one. */
    fun blocks(serverRole: Boolean = true): List<HelpBlock> {
        val common = listOf(
            HelpBlock(R.string.help_what_heading, R.string.help_what_body),
            HelpBlock(R.string.help_local_heading, R.string.help_local_body),
            HelpBlock(R.string.help_remote_heading, R.string.help_remote_body)
        )
        if (!serverRole) return common
        return common + HelpBlock(R.string.help_alerts_heading, R.string.help_alerts_body)
    }

    /** The closing honesty line; never a promise of uptime. */
    fun footnoteRes(): Int = R.string.help_footnote
}
