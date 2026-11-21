// SPDX-License-Identifier: Apache-2.0
package app.grom.server.referral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class InviteCodeTest {

    @Test
    fun `generated code uses only the unambiguous alphabet`() {
        val random = SecureRandom()
        repeat(200) {
            val code = InviteCode.generate(random)
            assertEquals(InviteCode.LENGTH, code.length)
            assertTrue(code, code.all { InviteCode.ALPHABET.indexOf(it) >= 0 })
        }
    }

    @Test
    fun `generated codes do not collide in a sample of 200`() {
        val random = SecureRandom()
        val codes = HashSet<String>()
        repeat(200) { codes.add(InviteCode.generate(random)) }
        assertEquals(200, codes.size)
    }

    @Test
    fun `generation clamps the requested length to the accepted range`() {
        val random = SecureRandom()
        assertEquals(InviteCode.MIN_LENGTH, InviteCode.generate(random, 0).length)
        assertEquals(InviteCode.MIN_LENGTH, InviteCode.generate(random, 1).length)
        assertEquals(InviteCode.MAX_LENGTH, InviteCode.generate(random, 99).length)
        assertEquals(12, InviteCode.generate(random, 12).length)
    }

    @Test
    fun `normalize folds case, spaces and separators`() {
        assertEquals("ABC123", InviteCode.normalize(" abc-123 "))
        assertEquals("ABC123", InviteCode.normalize("a b c 1 2 3"))
        assertEquals("ABC123", InviteCode.normalize("A_B_C_1_2_3"))
    }

    @Test
    fun `normalize applies the Crockford substitutions`() {
        assertEquals("1", InviteCode.normalize("I"))
        assertEquals("1", InviteCode.normalize("L"))
        assertEquals("0", InviteCode.normalize("O"))
        assertEquals("V", InviteCode.normalize("U"))
        assertEquals("HE110", InviteCode.normalize("hello"))
    }

    @Test
    fun `normalize drops characters that cannot be folded`() {
        assertEquals("AB12", InviteCode.normalize("A!B@1#2"))
        assertEquals("", InviteCode.normalize("!!!"))
        assertEquals("", InviteCode.normalize(null))
    }

    @Test
    fun `validation accepts only codes of an accepted length`() {
        assertFalse(InviteCode.isValid("ABC"))
        assertTrue(InviteCode.isValid("ABCD"))
        assertTrue(InviteCode.isValid("ABCDEFGHIJKL"))
        assertFalse(InviteCode.isValid("ABCDEFGHIJKLM"))
        assertFalse(InviteCode.isValid(""))
        assertFalse(InviteCode.isValid(null))
        assertFalse(InviteCode.isValid("    "))
    }

    @Test
    fun `a generated code round-trips through normalize unchanged`() {
        val random = SecureRandom()
        repeat(100) {
            val code = InviteCode.generate(random)
            assertEquals(code, InviteCode.normalize(code))
            assertTrue(InviteCode.isValid(code))
        }
    }
}
