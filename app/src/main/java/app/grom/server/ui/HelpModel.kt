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
 * The three answers an ordinary user needs:
 *   1. what Grom does at all;
 *   2. why most people need no external server;
 *   3. when an external SSH server helps, and what it is for.
 */
data class HelpBlock(val headingRes: Int, val bodyRes: Int)

object HelpModel {

    /** Exactly three blocks, always in this order. */
    fun blocks(): List<HelpBlock> = listOf(
        HelpBlock(R.string.help_what_heading, R.string.help_what_body),
        HelpBlock(R.string.help_local_heading, R.string.help_local_body),
        HelpBlock(R.string.help_remote_heading, R.string.help_remote_body)
    )

    /** The closing honesty line; never a promise of uptime. */
    fun footnoteRes(): Int = R.string.help_footnote
}
