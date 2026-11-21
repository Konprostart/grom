// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R

/**
 * The "How Grom works" section (D62, extended by D68).
 *
 * The content is static, fixed and ordered: there is nothing here that depends
 * on a status document, so the text can never claim that something is running.
 * It is kept out of the activity so the promises it makes are reviewable in one
 * place and testable on the JVM — [HelpModelTest] asserts the block count, the
 * order and that no block smuggles in an address, a price or a balance.
 *
 * The section sits at the very bottom of the page, immediately after the invite
 * card, and answers, in plain language, one question per block:
 *   1. what Grom does at all;
 *   2. why most people need no rented server;
 *   3. when an external SSH server helps, and what it is for;
 *   4. what the Nostr and Lightning settings are (and are not);
 *   5. what the local-project lamp means;
 *   6. how the Telegram alerts work;
 *   7. what Grom cannot do — the honest boundary.
 *
 * A `peripheral26` build never binds a socket and has no tunnel, no projects
 * and no alerts, so promising those blocks there would be the exact kind of lie
 * this section exists to avoid. It gets "what it does" and "what it cannot do".
 */
data class HelpBlock(val headingRes: Int, val bodyRes: Int)

object HelpModel {

    /** What every role is, and what no role can promise. */
    private val shared = listOf(
        HelpBlock(R.string.help_what_heading, R.string.help_what_body),
        HelpBlock(R.string.help_limits_heading, R.string.help_limits_body)
    )

    /** Server-only capabilities, in the order they appear in the menu. */
    private val serverOnly = listOf(
        HelpBlock(R.string.help_local_heading, R.string.help_local_body),
        HelpBlock(R.string.help_remote_heading, R.string.help_remote_body),
        HelpBlock(R.string.help_nostr_heading, R.string.help_nostr_body),
        HelpBlock(R.string.help_projects_heading, R.string.help_projects_body),
        HelpBlock(R.string.help_alerts_heading, R.string.help_alerts_body)
    )

    /**
     * Seven blocks with a server, two without. The order is fixed: "what is
     * this" first, the honest limit last, everything in between in the same
     * order as the menu.
     */
    fun blocks(serverRole: Boolean = true): List<HelpBlock> {
        if (!serverRole) return shared
        return listOf(shared[0]) + serverOnly + listOf(shared[1])
    }

    /** The closing honesty line; never a promise of uptime. */
    fun footnoteRes(): Int = R.string.help_footnote
}
