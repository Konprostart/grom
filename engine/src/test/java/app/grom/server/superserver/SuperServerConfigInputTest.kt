// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract an embedding host's `superSaveConfig` shares with the screen (D86, S5-R052).
 *
 * The point of these tests is that there is exactly *one* policy: every refusal
 * here is a refusal the standalone screen would produce, with the same code, and
 * every acceptance is a document the screen would have written.
 */
class SuperServerConfigInputTest {

    private val controlPort = 47001

    @Test
    fun `an empty document is refused`() {
        assertCode(SuperServerConfigInput.BAD_CONFIG, SuperServerConfigInput.parse(null, controlPort))
        assertCode(SuperServerConfigInput.BAD_CONFIG, SuperServerConfigInput.parse("   ", controlPort))
    }

    @Test
    fun `a document that is not a JSON object is refused`() {
        assertCode(SuperServerConfigInput.BAD_CONFIG, SuperServerConfigInput.parse("[]", controlPort))
        assertCode(SuperServerConfigInput.BAD_CONFIG, SuperServerConfigInput.parse("not json", controlPort))
    }

    @Test
    fun `an oversized document is refused before it is parsed`() {
        val huge = "{" + "x".repeat(SuperServerConfigInput.MAX_BYTES) + "}"
        assertCode(SuperServerConfigInput.BAD_CONFIG, SuperServerConfigInput.parse(huge, controlPort))
    }

    @Test
    fun `the screen's own document round-trips`() {
        val written = SuperServerConfig.defaults()
            .copy(enabled = true, bind = app.grom.server.core.BindMode.LOOPBACK, passwordHash = HASH)
            .toJson()

        val parsed = SuperServerConfigInput.parse(written, controlPort)

        assertTrue("expected acceptance, got $parsed", parsed is ConfigParse.Parsed)
        val config = (parsed as ConfigParse.Parsed).config
        assertTrue(config.enabled)
        assertEquals(HASH, config.passwordHash)
    }

    @Test
    fun `a non-loopback bind without a credential is refused exactly as the screen refuses it`() {
        val document = SuperServerConfig.defaults()
            .copy(
                enabled = true,
                bind = app.grom.server.core.BindMode.LAN,
                auth = AuthMode.KEY,
                keys = emptyList(),
                passwordHash = null
            )
            .toJson()

        assertCode("CREDENTIAL_REQUIRED", SuperServerConfigInput.parse(document, controlPort))
    }

    @Test
    fun `a public bind without the acknowledgement is refused`() {
        val document = SuperServerConfig.defaults()
            .copy(enabled = true, bind = app.grom.server.core.BindMode.PUBLIC, passwordHash = HASH)
            .toJson()

        assertCode("PUBLIC_NOT_ACKNOWLEDGED", SuperServerConfigInput.parse(document, controlPort))
    }

    @Test
    fun `the data plane may not take the control plane's port`() {
        val document = SuperServerConfig.defaults()
            .copy(enabled = true, port = controlPort, passwordHash = HASH)
            .toJson()

        assertCode("PORT_CONFLICT", SuperServerConfigInput.parse(document, controlPort))
    }

    @Test
    fun `a plaintext backend URL is refused`() {
        val document = SuperServerConfig.defaults()
            .copy(
                enabled = true,
                passwordHash = HASH,
                lightning = LightningSettings(kind = LightningKind.LNBITS, baseUrl = "http://lnbits.local")
            )
            .toJson()

        assertCode("INSECURE_BACKEND_URL", SuperServerConfigInput.parse(document, controlPort))
    }

    @Test
    fun `a malformed key is refused with the screen's code`() {
        val document = SuperServerConfig.defaults()
            .copy(
                enabled = true,
                auth = AuthMode.KEY,
                keys = listOf(ApiKeyConfig(keyId = "abc", label = "laptop", publicKey = "not-a-key"))
            )
            .toJson()

        assertCode("BAD_KEY", SuperServerConfigInput.parse(document, controlPort))
    }

    @Test
    fun `a disabled document is accepted without credentials`() {
        val document = SuperServerConfig.defaults().toJson()

        assertTrue(SuperServerConfigInput.parse(document, controlPort) is ConfigParse.Parsed)
    }

    private fun assertCode(expected: String, actual: ConfigParse) {
        assertTrue("expected a refusal, got $actual", actual is ConfigParse.Refused)
        assertEquals(expected, (actual as ConfigParse.Refused).code)
    }

    private companion object {
        /** Shape only: the store never sees a plaintext password. */
        const val HASH = "pbkdf2-sha256\$150000\$c2FsdA==\$aGFzaA=="
    }
}
