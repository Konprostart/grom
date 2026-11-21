// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair

class SshKeysTest {

    @Test
    fun `generates a parseable Ed25519 pair`() {
        val generated = SshKeys.generate()
        assertNotNull("the CC0 Ed25519 provider must be on the test classpath", generated)

        assertTrue(generated!!.privateKeyText.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertTrue(generated.privateKeyText.contains("END OPENSSH PRIVATE KEY"))
        assertTrue(generated.publicKeyLine.startsWith("ssh-ed25519 "))

        val pair = SshKeys.keyPairFromPrivateText(generated.privateKeyText)
        assertNotNull("the generated key must load through the same path as an import", pair)
        assertNotNull(pair!!.public)

        // The public line derived from the parsed pair is identical.
        val roundTripped = SshKeys.publicKeyLine(pair.public)
        assertNotNull(roundTripped)
        assertTrue(
            "expected '$roundTripped' to start with '${generated.publicKeyLine.substringBefore(" ")}'",
            roundTripped!!.startsWith("ssh-ed25519 ")
        )
    }

    @Test
    fun `the encoded key is unencrypted openssh and carries no fs permissions`() {
        val generated = SshKeys.generate("grom@test")!!
        val pair: KeyPair = SshKeys.keyPairFromPrivateText(generated.privateKeyText)!!
        assertTrue(generated.publicKeyLine.endsWith("grom@test"))
        assertTrue(SshKeys.fingerprint(pair.public).startsWith("SHA256:"))
    }

    @Test
    fun `two generations produce different keys`() {
        val one = SshKeys.generate()!!
        val two = SshKeys.generate()!!
        assertFalse(one.publicKeyLine == two.publicKeyLine)
    }

    @Test
    fun `malformed text is refused`() {
        assertNull(SshKeys.keyPairFromPrivateText(""))
        assertNull(SshKeys.keyPairFromPrivateText("not a key"))
        assertNull(SshKeys.keyPairFromPrivateText("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"))
        assertFalse(SshKeys.looksLikePrivateKey("hello"))
        assertTrue(SshKeys.looksLikePrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----"))
    }

    @Test
    fun `the fingerprint is stable for the same key`() {
        val generated = SshKeys.generate()!!
        val first = SshKeys.keyPairFromPrivateText(generated.privateKeyText)!!
        val second = SshKeys.keyPairFromPrivateText(generated.privateKeyText)!!
        assertTrue(SshKeys.fingerprint(first.public) == SshKeys.fingerprint(second.public))
    }
}
