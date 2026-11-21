// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHashTest {

    private val password = "correct horse battery"

    @Test
    fun `round trip accepts the same password`() {
        val hash = PasswordHash.create(password, FAST)
        assertTrue(hash.matches(password))
        assertTrue(PasswordHash.isEncoded(hash.value))
    }

    @Test
    fun `wrong password is false`() {
        val hash = PasswordHash.create(password, FAST)
        assertFalse(hash.matches(""))
        assertFalse(hash.matches("correct horse batterY"))
        assertFalse(hash.matches(password + "!"))
    }

    @Test
    fun `encoded value has the documented shape`() {
        val hash = PasswordHash.create(password, FAST)
        val fields = hash.value.split('$')
        assertEquals(4, fields.size)
        assertEquals("pbkdf2-sha256", fields[0])
        assertEquals(FAST.toString(), fields[1])
        assertEquals(16, Base64.getDecoder().decode(fields[2]).size)
        assertEquals(32, Base64.getDecoder().decode(fields[3]).size)
        assertEquals(hash.value, PasswordHash.decode(hash.value)?.value)
    }

    @Test
    fun `short password throws`() {
        assertEquals(8, PasswordHash.MIN_LENGTH)
        assertThrows(IllegalArgumentException::class.java) {
            PasswordHash.create("1234567", FAST)
        }
        // Exactly the minimum length is accepted.
        assertTrue(PasswordHash.create("12345678", FAST).matches("12345678"))
    }

    @Test
    fun `iterations outside the accepted range throw`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordHash.create(password, 9_999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PasswordHash.create(password, 1_000_001)
        }
    }

    @Test
    fun `default work factor is 150000`() {
        assertEquals(150_000, PasswordHash.ITERATIONS)
        val hash = PasswordHash.create(password)
        assertTrue(hash.value.startsWith("pbkdf2-sha256\$150000\$"))
        assertTrue(hash.matches(password))
    }

    @Test
    fun `two hashes of the same password differ`() {
        val first = PasswordHash.create(password, FAST)
        val second = PasswordHash.create(password, FAST)
        assertNotEquals(first.value, second.value)
        assertTrue(first.matches(password))
        assertTrue(second.matches(password))
    }

    @Test
    fun `decode rejects malformed encodings`() {
        val encoded = PasswordHash.create(password, FAST).value
        val fields = encoded.split('$')

        assertNull(PasswordHash.decode(null))
        assertNull(PasswordHash.decode(""))
        assertNull(PasswordHash.decode("pbkdf2-sha256"))
        assertNull(PasswordHash.decode("not-a-hash"))

        // Wrong scheme.
        assertNull(PasswordHash.decode(join("pbkdf2-sha512", fields[1], fields[2], fields[3])))

        // Wrong field count: too few, an extra field, and a trailing separator
        // (which would otherwise be dropped by a lenient split).
        assertNull(PasswordHash.decode(join(fields[0], fields[1], fields[2])))
        assertNull(PasswordHash.decode(encoded + "\$" + "extra"))
        assertNull(PasswordHash.decode(encoded + "\$"))

        // Invalid Base64 in either byte field.
        assertNull(PasswordHash.decode(join(fields[0], fields[1], "!!!!", fields[3])))
        assertNull(PasswordHash.decode(join(fields[0], fields[1], fields[2], "!!!!")))

        // Empty byte fields.
        assertNull(PasswordHash.decode(join(fields[0], fields[1], "", fields[3])))
        assertNull(PasswordHash.decode(join(fields[0], fields[1], fields[2], "")))

        // Salt outside 16..64 bytes and hash outside 32..64 bytes.
        assertNull(
            PasswordHash.decode(join(fields[0], fields[1], base64(ByteArray(8)), fields[3]))
        )
        assertNull(
            PasswordHash.decode(join(fields[0], fields[1], base64(ByteArray(65)), fields[3]))
        )
        assertNull(
            PasswordHash.decode(join(fields[0], fields[1], fields[2], base64(ByteArray(16))))
        )
        assertNull(
            PasswordHash.decode(join(fields[0], fields[1], fields[2], base64(ByteArray(65))))
        )
    }

    @Test
    fun `decode rejects tampered or oversized iteration counts`() {
        val fields = PasswordHash.create(password, FAST).value.split('$')

        for (iterations in listOf("9999", "0", "1000001", "abc", "-150000", "+150000", "", " ")) {
            assertNull(
                "iterations '$iterations' must be rejected",
                PasswordHash.decode(join(fields[0], iterations, fields[2], fields[3]))
            )
        }
        // Far beyond Int range: must not overflow into a valid value.
        assertNull(
            PasswordHash.decode(join(fields[0], "99999999999999999999", fields[2], fields[3]))
        )
        assertNull(
            PasswordHash.decode(join(fields[0], "15000000000000000000", fields[2], fields[3]))
        )
    }

    @Test
    fun `decode accepts the documented size range`() {
        val fields = PasswordHash.create(password, FAST).value.split('$')
        assertNotNull(
            PasswordHash.decode(join(fields[0], fields[1], fields[2], fields[3]))
        )
        // Boundary sizes are accepted by the parser even though no such hash
        // was produced here.
        assertNotNull(
            PasswordHash.decode(
                join(fields[0], fields[1], base64(ByteArray(64)), base64(ByteArray(64)))
            )
        )
    }

    @Test
    fun `isEncoded only accepts parseable values`() {
        assertTrue(PasswordHash.isEncoded(PasswordHash.create(password, FAST).value))
        assertFalse(PasswordHash.isEncoded(null))
        assertFalse(PasswordHash.isEncoded(""))
        assertFalse(PasswordHash.isEncoded("nope"))
    }

    @Test
    fun `dummy is fixed and matches nothing`() {
        val dummy = PasswordHash.dummy()
        assertTrue(PasswordHash.isEncoded(dummy.value))
        assertEquals(dummy.value, PasswordHash.dummy().value)
        assertFalse(dummy.matches(""))
        assertFalse(dummy.matches(password))
    }

    private fun join(vararg parts: String): String = parts.joinToString("\$")

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val FAST = 10_000
    }
}
