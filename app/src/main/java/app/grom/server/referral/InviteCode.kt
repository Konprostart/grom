// SPDX-License-Identifier: Apache-2.0
package app.grom.server.referral

import java.security.SecureRandom
import java.util.Locale

/**
 * Invite codes are local identifiers for sharing the app, not accounts (D50).
 *
 * The alphabet is Crockford base32 without the ambiguous letters: there is no
 * `I`, `L`, `O` or `U`, because those are read as `1`, `1`, `0` and `V` when a
 * human copies a code off a screen. [normalize] applies exactly that folding,
 * so a code typed in lower case, with spaces or with `I` instead of `1`, still
 * lands on the same value.
 *
 * Nothing in this file talks to a network. A code is random, local, and says
 * nothing about the device that generated it.
 */
object InviteCode {

    /** Shortest accepted code. Four characters are enough to recognise a typo. */
    const val MIN_LENGTH = 4

    /** Longest accepted code; also caps what a share link can carry. */
    const val MAX_LENGTH = 12

    /** Default generated length: 32^8 ≈ 1.1 × 10^12 possibilities. */
    const val LENGTH = 8

    /** 32 symbols: digits plus the unambiguous upper-case letters. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * Draws [length] symbols from a [SecureRandom]. The alphabet is exactly 32
     * long, so `nextInt(32)` is uniform: no modulo bias.
     */
    fun generate(random: SecureRandom = SecureRandom(), length: Int = LENGTH): String {
        val size = length.coerceIn(MIN_LENGTH, MAX_LENGTH)
        val builder = StringBuilder(size)
        repeat(size) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return builder.toString()
    }

    /**
     * Folds what a human actually types onto [ALPHABET]: case, spaces, dashes,
     * and the four characters the alphabet leaves out. Characters that cannot
     * be folded — punctuation, accented letters — are dropped, so validation is
     * always performed on the normalized value, never on the raw input.
     */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val builder = StringBuilder(raw.length)
        for (character in raw.uppercase(Locale.ROOT)) {
            val mapped = when (character) {
                'I', 'L' -> '1'
                'O' -> '0'
                'U' -> 'V'
                else -> character
            }
            if (ALPHABET.indexOf(mapped) >= 0) builder.append(mapped)
        }
        return builder.toString()
    }

    /** True when the input normalizes to a code of an accepted length. */
    fun isValid(raw: String?): Boolean {
        val normalized = normalize(raw)
        return normalized.length in MIN_LENGTH..MAX_LENGTH
    }
}
