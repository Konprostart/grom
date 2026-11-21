// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.core.BindMode
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.superserver.nostr.RemoteBridgeHint
import app.grom.server.superserver.plugins.lightning.ExternalNodeKind
import app.grom.server.superserver.plugins.lightning.ExternalNodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        ack: Boolean = false,
        npub: String? = null,
        relays: List<String> = emptyList(),
        bridge: RemoteBridgeHint? = null
    ) = SuperServerConfig(
        enabled = enabled,
        bind = bind,
        port = port,
        auth = auth,
        passwordHash = passwordHash,
        keys = keys,
        lightning = lightning,
        acknowledgePublicBind = ack,
        npub = npub,
        relays = relays,
        remoteBridge = bridge
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

    // ----------------------------------------------- reference config (D52–D54)

    private val validNpub =
        "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"

    @Test
    fun aValidNpubIsAcceptedAndAnInvalidOneIsRefused() {
        assertEquals(ConfigCheck.Ok, config(npub = validNpub).validate(47_001))
        assertEquals(
            ConfigCheck.Ok,
            config(npub = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e")
                .validate(47_001)
        )
        assertEquals("BAD_NPUB", invalidCode(config(npub = "npub1broken").validate(47_001)))
        assertEquals("BAD_NPUB", invalidCode(config(npub = "nsec1abc").validate(47_001)))
    }

    @Test
    fun relayListItemValidationIsExplicit() {
        assertEquals(
            ConfigCheck.Ok,
            config(relays = listOf("wss://a.example", "ws://127.0.0.1:7777")).validate(47_001)
        )
        assertEquals(
            "BAD_RELAY",
            invalidCode(config(relays = listOf("https://a.example")).validate(47_001))
        )
        val tooMany = (1..9).map { "wss://relay$it.example" }
        assertEquals("TOO_MANY_RELAYS", invalidCode(config(relays = tooMany).validate(47_001)))
    }

    @Test
    fun bridgeHintsAreValidatedForLengthAndSecrets() {
        assertEquals(
            ConfigCheck.Ok,
            config(bridge = RemoteBridgeHint("Mostro VPS", "tunnel")).validate(47_001)
        )
        assertEquals(
            "BAD_REMOTE_BRIDGE",
            invalidCode(
                config(bridge = RemoteBridgeHint("nsec1" + "a".repeat(40))).validate(47_001)
            )
        )
        assertEquals(
            "BAD_REMOTE_BRIDGE",
            invalidCode(config(bridge = RemoteBridgeHint("a".repeat(81))).validate(47_001))
        )
    }

    @Test
    fun referenceFieldsSurviveTheJsonRoundTrip() {
        val original = config(
            npub = validNpub,
            relays = listOf("wss://a.example", "ws://127.0.0.1:7777"),
            bridge = RemoteBridgeHint("Mostro VPS", "SSH reverse tunnel")
        )
        val decoded = SuperServerConfig.fromJson(original.toJson())!!
        assertEquals(validNpub, decoded.npub)
        assertEquals(original.relays, decoded.relays)
        assertEquals(original.remoteBridge, decoded.remoteBridge)
        assertEquals(original, decoded)
    }

    @Test
    fun malformedRelaysAreKeptSoValidationCanReportThem() {
        val decoded = SuperServerConfig.fromJson(
            """{"enabled":true,"passwordHash":"$encoded","relays":["https://bad.example","wss://ok.example"]}"""
        )!!
        assertEquals(2, decoded.relays.size)
        assertEquals("BAD_RELAY", invalidCode(decoded.validate(47_001)))
    }

    @Test
    fun sameRuntimePolicyIgnoresOnlyReferenceFields() {
        val base = config()
        assertTrue(base.sameRuntimePolicy(base.copy(npub = validNpub)))
        assertTrue(base.sameRuntimePolicy(base.copy(relays = listOf("wss://a.example"))))
        assertTrue(base.sameRuntimePolicy(base.copy(remoteBridge = RemoteBridgeHint("vps"))))
        // An external node is a reference too: editing it must not drop sessions.
        assertTrue(
            base.sameRuntimePolicy(
                base.copy(
                    lightning = base.lightning.copy(
                        externalNode = ExternalNodeRef(
                            ExternalNodeKind.LND,
                            "VPS node",
                            "node.example.com:10009"
                        )
                    )
                )
            )
        )
        assertFalse(base.sameRuntimePolicy(base.copy(port = 47_003)))
        assertFalse(base.sameRuntimePolicy(base.copy(auth = AuthMode.KEY)))
        assertFalse(base.sameRuntimePolicy(base.copy(enabled = false)))
        assertFalse(base.sameRuntimePolicy(base.copy(maxBodyBytes = 4_096)))
        assertFalse(
            base.sameRuntimePolicy(
                base.copy(lightning = LightningSettings(LightningKind.LNBITS, "https://x.example"))
            )
        )
    }

    @Test
    fun externalNodeReferenceIsValidatedAndRoundTrips() {
        val node = ExternalNodeRef(ExternalNodeKind.CLN, "Home node", "10.0.0.5:9736")
        val withNode = config().copy(
            lightning = config().lightning.copy(externalNode = node)
        )
        assertEquals(ConfigCheck.Ok, withNode.validate(47_001))

        val replayed = SuperServerConfig.fromJson(withNode.toJson())
        assertEquals(node, replayed?.lightning?.externalNode)

        val withoutKind = withNode.copy(
            lightning = withNode.lightning.copy(
                externalNode = ExternalNodeRef(ExternalNodeKind.NONE, "Home node", "")
            )
        )
        val refused = withoutKind.validate(47_001)
        assertTrue(refused is ConfigCheck.Invalid && refused.code == "BAD_EXTERNAL_NODE")

        val secretLabel = withNode.copy(
            lightning = withNode.lightning.copy(
                externalNode = ExternalNodeRef(ExternalNodeKind.LND, "nsec1abc", "")
            )
        )
        val refusedSecret = secretLabel.validate(47_001)
        assertTrue(refusedSecret is ConfigCheck.Invalid && refusedSecret.code == "BAD_EXTERNAL_NODE")
    }

    @Test
    fun anExternalNodeEndpointIsNotAUrl() {
        val urlNode = config().copy(
            lightning = config().lightning.copy(
                externalNode = ExternalNodeRef(
                    ExternalNodeKind.LND,
                    "node",
                    "https://node.example.com"
                )
            )
        )
        assertTrue(urlNode.validate(47_001) is ConfigCheck.Invalid)
    }
}
