// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.core.BindMode
import app.grom.server.superserver.auth.PasswordHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperServerConfigTest {

    private val encoded = PasswordHash.create("password-123", iterations = 10_000).value
    private val publicKey = ByteArray(32) { (it + 1).toByte() }
    private val keyBase64 = java.util.Base64.getEncoder().encodeToString(publicKey)
    private val keyId = ApiKeyConfig.deriveKeyId(keyBase64)!!

    private fun config(
        enabled: Boolean = true,
        bind: BindMode = BindMode.LOOPBACK,
        port: Int = 47_002,
        auth: AuthMode = AuthMode.BOTH,
        passwordHash: String? = encoded,
        keys: List<ApiKeyConfig> = emptyList(),
        lightning: LightningSettings = LightningSettings(),
        ack: Boolean = false
    ) = SuperServerConfig(
        enabled = enabled,
        bind = bind,
        port = port,
        auth = auth,
        passwordHash = passwordHash,
        keys = keys,
        lightning = lightning,
        acknowledgePublicBind = ack
    )

    private fun invalidCode(check: ConfigCheck): String =
        (check as? ConfigCheck.Invalid)?.code ?: error("expected Invalid, got $check")

    @Test
    fun disabledConfigIsAlwaysAcceptable() {
        assertEquals(ConfigCheck.Ok, SuperServerConfig().validate(47_001))
        assertEquals(ConfigCheck.Ok, config(enabled = false, port = 0).validate(47_001))
    }

    @Test
    fun loopbackWithoutCredentialIsRefused() {
        val code = invalidCode(config(passwordHash = null).validate(47_001))
        assertEquals("CREDENTIAL_REQUIRED", code)
    }

    @Test
    fun nonLoopbackWithoutCredentialIsRefused() {
        val code = invalidCode(
            config(bind = BindMode.LAN, passwordHash = null).validate(47_001)
        )
        assertEquals("CREDENTIAL_REQUIRED", code)
    }

    @Test
    fun publicBindRequiresExplicitAcknowledgement() {
        val code = invalidCode(config(bind = BindMode.PUBLIC).validate(47_001))
        assertEquals("PUBLIC_NOT_ACKNOWLEDGED", code)
        assertEquals(ConfigCheck.Ok, config(bind = BindMode.PUBLIC, ack = true).validate(47_001))
    }

    @Test
    fun authModeRequiresTheMatchingCredential() {
        assertEquals(
            "PASSWORD_REQUIRED",
            invalidCode(config(auth = AuthMode.PASSWORD, passwordHash = null).validate(47_001))
        )
        assertEquals(
            "KEY_REQUIRED",
            invalidCode(config(auth = AuthMode.KEY, passwordHash = null).validate(47_001))
        )
    }

    @Test
    fun portMustBeValidAndDistinctFromTheControlPlane() {
        assertEquals("BAD_PORT", invalidCode(config(port = 0).validate(47_001)))
        assertEquals("PORT_CONFLICT", invalidCode(config(port = 47_001).validate(47_001)))
    }

    @Test
    fun backendUrlMustBeHttps() {
        val insecure = LightningSettings(
            kind = LightningKind.LNBITS,
            baseUrl = "http://lnbits.example",
            apiKeySealed = "sealed"
        )
        assertEquals(
            "INSECURE_BACKEND_URL",
            invalidCode(config(lightning = insecure).validate(47_001))
        )
        val secure = insecure.copy(baseUrl = "https://lnbits.example")
        assertEquals(ConfigCheck.Ok, config(lightning = secure).validate(47_001))
    }

    @Test
    fun malformedKeysAreRejectedAndDuplicatesDetected() {
        val shortKey = ApiKeyConfig(keyId = "abcd1234", label = "", publicKey = "AAAA")
        assertEquals("BAD_KEY", invalidCode(config(keys = listOf(shortKey)).validate(47_001)))

        val duplicate = listOf(
            ApiKeyConfig(keyId, "one", keyBase64),
            ApiKeyConfig(keyId, "two", keyBase64)
        )
        assertEquals("DUPLICATE_KEY", invalidCode(config(keys = duplicate).validate(47_001)))
    }

    @Test
    fun keyIdIsDerivedFromThePublicKey() {
        assertEquals(16, keyId.length)
        assertTrue(keyId.all { it in "0123456789abcdef" })
        assertNotNull(ApiKeyConfig.decodePublicKey(keyBase64))
        assertNull(ApiKeyConfig.decodePublicKey("not-base64"))
    }

    @Test
    fun jsonRoundTripPreservesTheWholeConfiguration() {
        val original = config(
            bind = BindMode.PUBLIC,
            auth = AuthMode.KEY,
            passwordHash = null,
            keys = listOf(ApiKeyConfig(keyId, "laptop", keyBase64)),
            lightning = LightningSettings(
                kind = LightningKind.LNBITS,
                baseUrl = "https://lnbits.example",
                apiKeySealed = "ciphertext"
            ),
            ack = true
        ).copy(maxBodyBytes = 4_096, maxConnections = 8, maxSseClients = 2)
        val decoded = SuperServerConfig.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun malformedJsonDegradesToDefaults() {
        assertNull(SuperServerConfig.fromJson("{"))
        assertNull(SuperServerConfig.fromJson("[]"))
        val partial = SuperServerConfig.fromJson("""{"enabled":true}""")
        assertNotNull(partial)
        assertEquals(BindMode.LOOPBACK, partial!!.bind)
        assertEquals(AuthMode.BOTH, partial.auth)
    }
}
