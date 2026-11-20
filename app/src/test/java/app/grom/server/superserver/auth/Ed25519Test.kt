// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

class Ed25519Test {

    private data class Vector(
        val name: String,
        val seed: String,
        val publicKey: String,
        val message: String,
        val signature: String
    )

    /** RFC 8032 section 7.1, test vectors 1, 2, 3 and SHA(abc). */
    private val vectors = listOf(
        Vector(
            name = "TEST 1 (empty message)",
            seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            message = "",
            signature = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        ),
        Vector(
            name = "TEST 2 (message 0x72)",
            seed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            publicKey = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            message = "72",
            signature = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        ),
        Vector(
            name = "TEST 3 (message 0xaf82)",
            seed = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            publicKey = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            message = "af82",
            signature = "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
        ),
        Vector(
            name = "TEST SHA(abc)",
            seed = "833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42",
            publicKey = "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf",
            message = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            signature = "dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b589" +
                "09351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704"
        )
    )

    @Test
    fun `rfc 8032 section 7_1 vectors match`() {
        for (vector in vectors) {
            val seed = hex(vector.seed)
            val publicKey = hex(vector.publicKey)
            val message = hex(vector.message)
            val signature = hex(vector.signature)

            assertArrayEquals(
                "${vector.name}: public key",
                publicKey,
                Ed25519.derivePublicKey(seed)
            )
            assertArrayEquals(
                "${vector.name}: signature",
                signature,
                Ed25519.sign(seed, message)
            )
            assertTrue(
                "${vector.name}: verification",
                Ed25519.verify(publicKey, message, signature)
            )
        }
    }

    @Test
    fun `signature sizes match the constants`() {
        val seed = Ed25519.generatePrivateSeed()
        val publicKey = Ed25519.derivePublicKey(seed)
        val signature = Ed25519.sign(seed, ByteArray(0))
        assertEquals(Ed25519.PRIVATE_SEED_BYTES, seed.size)
        assertEquals(Ed25519.PUBLIC_KEY_BYTES, publicKey.size)
        assertEquals(Ed25519.SIGNATURE_BYTES, signature.size)
    }

    @Test
    fun `flipped signature byte is rejected`() {
        val vector = vectors[1]
        val publicKey = hex(vector.publicKey)
        val message = hex(vector.message)
        val signature = hex(vector.signature)
        val tampered = signature.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(Ed25519.verify(publicKey, message, tampered))
    }

    @Test
    fun `flipped message byte is rejected`() {
        val vector = vectors[1]
        val publicKey = hex(vector.publicKey)
        val message = hex(vector.message)
        val signature = hex(vector.signature)
        val tampered = message.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(Ed25519.verify(publicKey, tampered, signature))
    }

    @Test
    fun `wrong public key is rejected`() {
        val first = vectors[0]
        val second = vectors[1]
        val signature = hex(first.signature)
        val message = hex(first.message)
        // Same message and signature, but the key of another test vector.
        assertFalse(Ed25519.verify(hex(second.publicKey), message, signature))
    }

    @Test
    fun `invalid signature length is rejected`() {
        val vector = vectors[0]
        val signature = hex(vector.signature)
        assertFalse(Ed25519.verify(hex(vector.publicKey), hex(vector.message), signature.copyOf(63)))
        assertFalse(Ed25519.verify(hex(vector.publicKey), hex(vector.message), signature.copyOf(65)))
        assertFalse(Ed25519.verify(hex(vector.publicKey), hex(vector.message), ByteArray(0)))
    }

    @Test
    fun `invalid public key length is rejected`() {
        val vector = vectors[0]
        val publicKey = hex(vector.publicKey)
        val signature = hex(vector.signature)
        val message = hex(vector.message)
        assertFalse(Ed25519.verify(publicKey.copyOf(31), message, signature))
        assertFalse(Ed25519.verify(publicKey.copyOf(33), message, signature))
        assertFalse(Ed25519.verify(ByteArray(0), message, signature))
    }

    @Test
    fun `non-canonical s is rejected`() {
        val vector = vectors[0]
        val publicKey = hex(vector.publicKey)
        val message = hex(vector.message)

        val allOnes = hex(vector.signature).copyOf()
        for (i in 32 until 64) allOnes[i] = 0xff.toByte()
        assertFalse(Ed25519.verify(publicKey, message, allOnes))

        // S encoded as the group order L itself is also out of range.
        val sIsL = hex(vector.signature).copyOf()
        val order = java.math.BigInteger.valueOf(2).pow(252)
            .add(java.math.BigInteger("27742317777372353535851937790883648493"))
        val littleEndianL = ByteArray(32)
        var remaining = order
        for (i in 0 until 32) {
            littleEndianL[i] = remaining.and(java.math.BigInteger.valueOf(0xff)).toByte()
            remaining = remaining.shiftRight(8)
        }
        System.arraycopy(littleEndianL, 0, sIsL, 32, 32)
        assertFalse(Ed25519.verify(publicKey, message, sIsL))
    }

    @Test
    fun `undecodable public key is rejected`() {
        val message = ByteArray(0)
        val signature = ByteArray(64)
        val notOnCurve = ByteArray(32) { 0x7f.toByte() }
        assertFalse(Ed25519.verify(notOnCurve, message, signature))
        // y >= p after clearing the sign bit.
        assertFalse(Ed25519.verify(ByteArray(32) { 0xff.toByte() }, message, signature))
    }

    @Test
    fun `key id is sixteen lowercase hex characters and deterministic`() {
        val publicKey = hex(vectors[0].publicKey)
        val keyId = Ed25519.keyId(publicKey)
        assertEquals(16, keyId.length)
        assertTrue(keyId.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(keyId, Ed25519.keyId(publicKey))

        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        assertEquals(toHex(digest.copyOf(8)), keyId)

        val other = Ed25519.keyId(hex(vectors[1].publicKey))
        assertTrue(keyId != other)
    }

    @Test
    fun `key id rejects a wrong length key`() {
        assertThrows { Ed25519.keyId(ByteArray(31)) }
        assertThrows { Ed25519.keyId(ByteArray(33)) }
    }

    @Test
    fun `derive public key rejects a wrong length seed`() {
        assertThrows { Ed25519.derivePublicKey(ByteArray(31)) }
        assertThrows { Ed25519.derivePublicKey(ByteArray(33)) }
        assertThrows { Ed25519.derivePublicKey(ByteArray(0)) }
    }

    @Test
    fun `sign rejects a wrong length seed`() {
        assertThrows { Ed25519.sign(ByteArray(31), ByteArray(0)) }
    }

    @Test
    fun `round trip over random seeds and messages`() {
        val random = SecureRandom()
        repeat(20) {
            val seed = Ed25519.generatePrivateSeed(random)
            val message = ByteArray(random.nextInt(201))
            random.nextBytes(message)

            val publicKey = Ed25519.derivePublicKey(seed)
            val signature = Ed25519.sign(seed, message)

            assertEquals(Ed25519.PUBLIC_KEY_BYTES, publicKey.size)
            assertEquals(Ed25519.SIGNATURE_BYTES, signature.size)
            assertTrue(Ed25519.verify(publicKey, message, signature))
            assertArrayEquals(publicKey, Ed25519.derivePublicKey(seed))
            assertArrayEquals(signature, Ed25519.sign(seed, message))
        }
    }

    private inline fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    private fun hex(value: String): ByteArray {
        val clean = value.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            val high = Character.digit(clean[2 * i], 16)
            val low = Character.digit(clean[2 * i + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex" }
            ((high shl 4) or low).toByte()
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            sb.append(chars[value ushr 4]).append(chars[value and 0x0f])
        }
        return sb.toString()
    }
}
