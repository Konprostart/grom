// SPDX-License-Identifier: Apache-2.0
package app.grom.server.referral

import android.content.Context

/**
 * The device's referral record (D50).
 *
 * Three facts live here, and they never leave the device:
 *   - the code this device generated and shares,
 *   - the code that was pasted in by the operator, if any,
 *   - how many times the operator copied or shared the invite on this device.
 *
 * There is no referral backend, no account, no attribution and nothing owed.
 * [summary] exists so the loopback control plane (and the authenticated
 * `/v1/status` of the super server) can show the operator their own record.
 * The unauthenticated `/v1/health` never carries any of it.
 */
object ReferralStore {

    private const val FILE = "grom.referral"
    private const val KEY_CODE = "code"
    private const val KEY_REFERRER = "referrer"
    private const val KEY_SHARES = "shares"

    /** What happened when an entered code was submitted. */
    sealed interface Redeem {
        data class Applied(val code: String) : Redeem
        data object Invalid : Redeem
        data object OwnCode : Redeem
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * This device's code, generated once at random and then kept. A
     * stored value that no longer validates is replaced rather than returned,
     * so a corrupted preference file cannot produce an unshareable code.
     */
    fun code(context: Context): String {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_CODE, null)
        if (stored != null && InviteCode.isValid(stored)) {
            val normalized = InviteCode.normalize(stored)
            if (normalized != stored) prefs.edit().putString(KEY_CODE, normalized).apply()
            return normalized
        }
        val generated = InviteCode.generate()
        prefs.edit().putString(KEY_CODE, generated).apply()
        return generated
    }

    /** The code that invited this device, or null when it was never set. */
    fun referrer(context: Context): String? =
        prefs(context).getString(KEY_REFERRER, null)?.let { stored ->
            InviteCode.normalize(stored).takeIf { InviteCode.isValid(it) }
        }

    /**
     * Validates and stores an entered code. Grom cannot verify that a code
     * exists — there is no registry — so "applied" means exactly "stored
     * locally", and the UI says so.
     */
    fun redeem(context: Context, raw: String?): Redeem {
        val normalized = InviteCode.normalize(raw)
        if (normalized.isEmpty()) return Redeem.Invalid
        if (!InviteCode.isValid(normalized)) return Redeem.Invalid
        if (normalized == code(context)) return Redeem.OwnCode
        prefs(context).edit().putString(KEY_REFERRER, normalized).apply()
        return Redeem.Applied(normalized)
    }

    fun clearReferrer(context: Context) {
        prefs(context).edit().remove(KEY_REFERRER).apply()
    }

    /** Number of copy/share actions started from this device. Local only. */
    fun shares(context: Context): Int = prefs(context).getInt(KEY_SHARES, 0).coerceAtLeast(0)

    /** Counts one copy or share action and returns the new total. */
    fun recordShare(context: Context): Int {
        val prefs = prefs(context)
        val next = shares(context) + 1
        prefs.edit().putInt(KEY_SHARES, next).apply()
        return next
    }

    /**
     * The whole local record as a plain map, for the status endpoints. Keys are
     * stable because the SDK consumer and the docs refer to them.
     */
    fun summary(context: Context): Map<String, Any?> = mapOf(
        "code" to code(context),
        "referredBy" to referrer(context),
        "shares" to shares(context),
        "rewards" to REWARDS_COMING_SOON
    )

    /**
     * The only honest rewards state there is: none has shipped. Clients must
     * render this as "coming soon" and never as an amount.
     */
    const val REWARDS_COMING_SOON: String = "coming_soon"
}
