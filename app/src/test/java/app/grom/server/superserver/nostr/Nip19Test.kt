// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.nostr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Nip19Test {

    private val vectorNpub =
        "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    private val vectorHex =
        "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"

    @Test
    fun acceptsTheNip19Vector() {
        assertEquals(vectorNpub, Nip19.normalizeNpub(vectorNpub))
    }

    @Test
    fun acceptsHexAndCanonicalisesToBech32() {
        assertEquals(vectorNpub, Nip19.normalizeNpub(vectorHex))
        assertEquals(vectorNpub, Nip19.normalizeNpub(vectorHex.uppercase()))
    }

    @Test
    fun decodeReturnsTheExpectedKey() {
        val bytes = Nip19.decodeNpub(vectorNpub)
        assertEquals(vectorHex, bytes!!.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun uppercaseIsAccepted() {
        assertEquals(vectorNpub, Nip19.normalizeNpub(vectorNpub.uppercase()))
    }

    @Test
    fun mixedCaseIsRejected() {
        val mixed = "nPub" + vectorNpub.substring(4)
        assertNull(Nip19.normalizeNpub(mixed))
    }

    @Test
    fun aCorruptChecksumIsRejected() {
        val last = vectorNpub.last()
        val corrupt = vectorNpub.dropLast(1) + if (last == 'q') 'p' else 'q'
        assertNull(Nip19.normalizeNpub(corrupt))
    }

    @Test
    fun otherHumanReadablePrefixesAreRejected() {
        val payload = Nip19.decodeNpub(vectorNpub)!!
        assertNull(Nip19.normalizeNpub(bech32Encode("nsec", payload)))
        assertNull(Nip19.normalizeNpub(bech32Encode("note", payload)))
        assertNull(Nip19.normalizeNpub(bech32Encode("nprofile", payload)))
    }

    @Test
    fun aPayloadThatIsNot32BytesIsRejected() {
        assertNull(Nip19.normalizeNpub(bech32Encode("npub", ByteArray(20) { 1 })))
        assertNull(Nip19.normalizeNpub(bech32Encode("npub", ByteArray(33) { 1 })))
    }

    @Test
    fun blanksAndOversizeInputAreRejected() {
        assertNull(Nip19.normalizeNpub(null))
        assertNull(Nip19.normalizeNpub(""))
        assertNull(Nip19.normalizeNpub("   "))
        assertNull(Nip19.normalizeNpub("npub"))
        assertNull(Nip19.normalizeNpub("a".repeat(Nip19.MAX_INPUT + 1)))
    }

    @Test
    fun encodingIsStable() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val encoded = Nip19.encode(key)
        assertArrayEquals(key, Nip19.decodeNpub(encoded))
        assertEquals(encoded, Nip19.normalizeNpub(encoded))
    }

    // ------------------------------------------------ independent bech32 encoder

    private fun bech32Encode(hrp: String, bytes: ByteArray): String {
        val data = convertBits(bytes.map { it.toInt() and 0xff }, 8, 5, pad = true)!!
        val mod = polymod(hrpExpand(hrp) + data + IntArray(6)) xor 1
        val builder = StringBuilder(hrp).append('1')
        for (value in data) builder.append(CHARSET[value])
        for (index in 0 until 6) {
            builder.append(CHARSET[(mod ushr (5 * (5 - index))) and 31])
        }
        return builder.toString()
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl to) - 1
        val out = ArrayList<Int>()
        for (value in data) {
            accumulator = (accumulator shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((accumulator ushr bits) and maxValue)
            }
        }
        if (pad && bits > 0) out.add((accumulator shl (to - bits)) and maxValue)
        return out
    }

    private fun polymod(values: IntArray): Int {
        val generators = intArrayOf(
            0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
        )
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (index in 0 until 5) {
                if ((top ushr index) and 1 == 1) checksum = checksum xor generators[index]
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

    private companion object {
        const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    }
}
