// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.nostr

/**
 * NIP-19 `npub` validation (D52).
 *
 * Grom stores the operator's **public** Nostr key as reference configuration for
 * an external bridge. It never signs, never derives a key and never opens a
 * relay connection, so this file is a pure decoder: bech32 (BIP-173, **not**
 * bech32m) plus the 32-byte payload check.
 *
 * Accepted inputs, normalised to the canonical lowercase `npub1…` form:
 *   - a valid bech32 `npub`;
 *   - a 64-character hex public key (NIP-19 says clients should accept both).
 *
 * Rejected: every other human-readable prefix (`nsec1`, `note1`, `nprofile1`,
 * `nevent1`, `naddr1`), a bad checksum, mixed case, non-32-byte payloads and
 * anything longer than [MAX_INPUT]. There is no secp256k1 point validation: the
 * key is never used to verify an operation here, so a 32-byte payload is the
 * whole contract.
 */
object Nip19 {

    const val PUBLIC_KEY_BYTES: Int = 32
    const val MAX_INPUT: Int = 5_000

    private const val HRP: String = "npub"
    private const val CHARSET: String = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val CHECKSUM_LENGTH: Int = 6
    private const val BECH32_CONST: Int = 1
    private val GENERATORS = intArrayOf(
        0x3b6a57b2,
        0x26508e6d,
        0x1ea119fa,
        0x3d4233dd,
        0x2a1462b3
    )

    /** Returns the canonical `npub1…` form, or null when the input is not a key. */
    fun normalizeNpub(input: String?): String? {
        val payload = decodeNpub(input) ?: return null
        return encode(payload)
    }

    /** Returns the raw 32-byte public key, or null when the input is not a key. */
    fun decodeNpub(input: String?): ByteArray? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_INPUT) return null
        if (isHexKey(trimmed)) return decodeHex(trimmed)
        val payload = decodeBech32(trimmed) ?: return null
        return payload.takeIf { it.size == PUBLIC_KEY_BYTES }
    }

    /** Encodes a 32-byte public key as `npub1…`. */
    fun encode(payload: ByteArray): String {
        require(payload.size == PUBLIC_KEY_BYTES) { "npub payload must be 32 bytes" }
        val data = convertBits(payload.map { it.toInt() and 0xff }, 8, 5, pad = true)
            ?: error("32 bytes always convert to bech32 data")
        val checksum = createChecksum(HRP, data)
        val builder = StringBuilder(HRP).append('1')
        for (value in data) builder.append(CHARSET[value])
        for (value in checksum) builder.append(CHARSET[value])
        return builder.toString()
    }

    // ------------------------------------------------------------------ bech32

    private fun decodeBech32(input: String): ByteArray? {
        // Mixed case is explicitly invalid in bech32; all-uppercase is accepted
        // and normalised.
        if (input != input.lowercase() && input != input.uppercase()) return null
        val lower = input.lowercase()
        val separator = lower.lastIndexOf('1')
        if (separator < 1 || separator + 1 + CHECKSUM_LENGTH > lower.length) return null

        val hrp = lower.substring(0, separator)
        if (hrp != HRP) return null
        if (hrp.length !in 1..83) return null

        val dataPart = lower.substring(separator + 1)
        val data = IntArray(dataPart.length)
        for (index in dataPart.indices) {
            val value = CHARSET.indexOf(dataPart[index])
            if (value < 0) return null
            data[index] = value
        }
        if (!verifyChecksum(hrp, data)) return null

        val payload = data.copyOfRange(0, data.size - CHECKSUM_LENGTH).toList()
        val bytes = convertBits(payload, 5, 8, pad = false) ?: return null
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }

    private fun isHexKey(text: String): Boolean =
        text.length == PUBLIC_KEY_BYTES * 2 && text.all { isHexDigit(it) }

    private fun isHexDigit(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

    private fun decodeHex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val high = Character.digit(text[index * 2], 16)
            val low = Character.digit(text[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun polymod(values: IntArray): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (index in 0 until 5) {
                if ((top ushr index) and 1 == 1) checksum = checksum xor GENERATORS[index]
            }
        }
        return checksum
    }

    private fun hrpExpand(hrp: String): IntArray {
        val expanded = IntArray(hrp.length * 2 + 1)
        for (index in hrp.indices) expanded[index] = hrp[index].code ushr 5
        expanded[hrp.length] = 0
        for (index in hrp.indices) expanded[hrp.length + 1 + index] = hrp[index].code and 31
        return expanded
    }

    private fun createChecksum(hrp: String, data: List<Int>): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(CHECKSUM_LENGTH)
        val mod = polymod(values) xor BECH32_CONST
        return IntArray(CHECKSUM_LENGTH) { index ->
            (mod ushr (5 * (CHECKSUM_LENGTH - 1 - index))) and 31
        }
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean =
        polymod(hrpExpand(hrp) + data) == BECH32_CONST

    /**
     * BIP-173 base conversion. With `pad = false` any leftover padding must be
     * zero and shorter than one source group; otherwise the value is malformed.
     */
    private fun convertBits(
        data: List<Int>,
        from: Int,
        to: Int,
        pad: Boolean
    ): List<Int>? {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl to) - 1
        val out = ArrayList<Int>((data.size * from + to - 1) / to)
        for (value in data) {
            if (value < 0 || (value ushr from) != 0) return null
            accumulator = (accumulator shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((accumulator ushr bits) and maxValue)
            }
        }
        if (pad) {
            if (bits > 0) out.add((accumulator shl (to - bits)) and maxValue)
        } else {
            if (bits >= from) return null
            if (((accumulator shl (to - bits)) and maxValue) != 0) return null
        }
        return out
    }
}
