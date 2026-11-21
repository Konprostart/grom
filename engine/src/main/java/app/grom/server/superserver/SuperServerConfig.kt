// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.core.BindMode
import app.grom.server.core.SuperBindPolicy
import app.grom.server.http.HttpParser
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.nostr.Nip19
import app.grom.server.superserver.nostr.RelayUrl
import app.grom.server.superserver.nostr.RemoteBridgeHint
import app.grom.server.superserver.plugins.lightning.ExternalNodeRef
import java.util.Base64

/**
 * Configuration of the public data plane (D39, S5-R001…S5-R019).
 *
 * The defaults are intentionally the safest possible ones: disabled, loopback
 * only, no credential. Enabling the server and moving it off loopback are two
 * separate, explicit operator decisions, and an off-loopback bind is refused
 * unless at least one credential exists.
 */
enum class AuthMode {
    /** Password login mints a short-lived session token. */
    PASSWORD,

    /** Ed25519 request signatures only. No password is stored. */
    KEY,

    /** Either credential is accepted. */
    BOTH
}

enum class LightningKind(val wireName: String) {
    NONE("none"),
    LNBITS("lnbits");

    companion object {
        fun fromWire(value: String?): LightningKind =
            entries.firstOrNull { it.wireName == value?.lowercase() } ?: NONE
    }
}

data class ApiKeyConfig(
    val keyId: String,
    val label: String,
    val publicKey: String,
    val enabled: Boolean = true
) {

    fun decodedPublicKey(): ByteArray? = decodePublicKey(publicKey)

    companion object {

        const val PUBLIC_KEY_BYTES: Int = 32

        /** Accepts standard or URL-safe base64; returns null unless it is 32 bytes. */
        fun decodePublicKey(value: String?): ByteArray? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.length > 128) return null
            for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
                val bytes = runCatching { decoder.decode(trimmed) }.getOrNull()
                if (bytes != null && bytes.size == PUBLIC_KEY_BYTES) return bytes
            }
            return null
        }

        /** Derives the wire key id from the raw key; null when the key is malformed. */
        fun deriveKeyId(publicKey: String?): String? =
            decodePublicKey(publicKey)?.let { Ed25519.keyId(it) }
    }
}

data class LightningSettings(
    val kind: LightningKind = LightningKind.NONE,
    val baseUrl: String = "",
    val timeoutMs: Int = 8_000,
    /** AES-GCM ciphertext from [app.grom.server.store.SecretBox]; never plaintext. */
    val apiKeySealed: String? = null,
    /**
     * A description of a node that lives on the operator's own server (D63).
     * Reference only: no gRPC, no NWC client, no socket. Its endpoint never
     * leaves the device.
     */
    val externalNode: ExternalNodeRef = ExternalNodeRef()
) {
    fun isConfigured(): Boolean = kind != LightningKind.NONE && baseUrl.isNotBlank()
}

sealed class ConfigCheck {
    data object Ok : ConfigCheck()
    data class Invalid(val code: String, val message: String) : ConfigCheck()
}

data class SuperServerConfig(
    val enabled: Boolean = false,
    val bind: BindMode = BindMode.LOOPBACK,
    val port: Int = SuperBindPolicy.DEFAULT_PORT,
    val auth: AuthMode = AuthMode.BOTH,
    /** PBKDF2 encoded string; a hash, never the password. */
    val passwordHash: String? = null,
    val keys: List<ApiKeyConfig> = emptyList(),
    val lightning: LightningSettings = LightningSettings(),
    val acknowledgePublicBind: Boolean = false,
    val sessionTtlMs: Long = 12L * 60 * 60 * 1000,
    val maxBodyBytes: Int = HttpParser.MAX_BODY_BYTES,
    val maxConnections: Int = 16,
    val maxSseClients: Int = 4,
    /**
     * Reference configuration for an external bridge (D52). None of it opens a
     * connection from Grom: `npub` is a public key, `relays` is a list of relay
     * URLs an external VPS may use, and `remoteBridge` is a free-text label for
     * a tunnel the operator runs elsewhere.
     */
    val npub: String? = null,
    val relays: List<String> = emptyList(),
    val remoteBridge: RemoteBridgeHint? = null
) {

    fun enabledKeys(): List<ApiKeyConfig> = keys.filter { it.enabled }

    /**
     * True when [other] differs only in the reference fields (npub, relays,
     * remoteBridge). Such a change can be applied without stopping the listener,
     * so active sessions and SSE streams survive (D54).
     */
    fun sameRuntimePolicy(other: SuperServerConfig): Boolean =
        runtimePolicy() == other.runtimePolicy()

    private fun runtimePolicy(): SuperServerConfig = copy(
        npub = null,
        relays = emptyList(),
        remoteBridge = null,
        lightning = lightning.copy(externalNode = ExternalNodeRef())
    )

    fun hasCredential(): Boolean =
        !passwordHash.isNullOrBlank() || enabledKeys().isNotEmpty()

    /**
     * The start-time gate. Everything that would make the listener unsafe or
     * useless is rejected here, with a stable code the operator can act on.
     * A refusal is reported in `/v1/status`; the server never "starts anyway".
     */
    fun validate(controlPort: Int): ConfigCheck {
        // A disabled server exposes nothing, so it has nothing to refuse.
        if (!enabled) return ConfigCheck.Ok
        if (port !in 1..65535) {
            return ConfigCheck.Invalid("BAD_PORT", "port must be between 1 and 65535")
        }
        if (port == controlPort) {
            return ConfigCheck.Invalid(
                "PORT_CONFLICT",
                "the super server must not share the control plane's port ($controlPort)"
            )
        }
        if (maxBodyBytes !in 1_024..HttpParser.MAX_BODY_BYTES) {
            return ConfigCheck.Invalid(
                "BAD_BODY_LIMIT",
                "maxBodyBytes must be between 1024 and ${HttpParser.MAX_BODY_BYTES}"
            )
        }
        if (maxConnections !in 1..64) {
            return ConfigCheck.Invalid("BAD_CONNECTION_LIMIT", "maxConnections must be 1..64")
        }
        if (maxSseClients !in 1..16) {
            return ConfigCheck.Invalid("BAD_SSE_LIMIT", "maxSseClients must be 1..16")
        }
        if (sessionTtlMs !in 60_000..7L * 24 * 60 * 60 * 1000) {
            return ConfigCheck.Invalid("BAD_SESSION_TTL", "sessionTtlMs must be 1 minute .. 7 days")
        }

        if (bind != BindMode.LOOPBACK && !hasCredential()) {
            return ConfigCheck.Invalid(
                "CREDENTIAL_REQUIRED",
                "a non-loopback bind requires a password or an API key"
            )
        }
        if (bind == BindMode.PUBLIC && !acknowledgePublicBind) {
            return ConfigCheck.Invalid(
                "PUBLIC_NOT_ACKNOWLEDGED",
                "binding every interface requires acknowledgePublicBind=true"
            )
        }

        when (auth) {
            AuthMode.PASSWORD -> if (passwordHash.isNullOrBlank()) {
                return ConfigCheck.Invalid("PASSWORD_REQUIRED", "password auth is selected but no password is set")
            }
            AuthMode.KEY -> if (enabledKeys().isEmpty()) {
                return ConfigCheck.Invalid("KEY_REQUIRED", "key auth is selected but no key is enabled")
            }
            AuthMode.BOTH -> if (!hasCredential()) {
                return ConfigCheck.Invalid(
                    "CREDENTIAL_REQUIRED",
                    "auth mode BOTH needs a password or an enabled key"
                )
            }
        }

        val seen = HashSet<String>()
        for (key in keys) {
            if (key.keyId.isBlank()) {
                return ConfigCheck.Invalid("BAD_KEY", "every API key needs a keyId")
            }
            if (key.decodedPublicKey() == null) {
                return ConfigCheck.Invalid(
                    "BAD_KEY",
                    "key '${key.keyId}' is not a 32-byte base64 Ed25519 public key"
                )
            }
            if (!seen.add(key.keyId)) {
                return ConfigCheck.Invalid("DUPLICATE_KEY", "duplicate keyId '${key.keyId}'")
            }
        }

        if (lightning.kind != LightningKind.NONE) {
            if (lightning.timeoutMs !in 1_000..30_000) {
                return ConfigCheck.Invalid("BAD_BACKEND_TIMEOUT", "backend timeout must be 1..30 seconds")
            }
            val url = lightning.baseUrl.trim()
            if (!url.startsWith("https://")) {
                return ConfigCheck.Invalid(
                    "INSECURE_BACKEND_URL",
                    "the Lightning backend URL must use https://; plain http would expose its API key"
                )
            }
            if (url.length > 512 || url.any { it.isWhitespace() }) {
                return ConfigCheck.Invalid("BAD_BACKEND_URL", "the backend URL is not a valid URL")
            }
        }

        // Reference configuration (D52). It is never dialled, but a bad value
        // would still travel to an external bridge and confuse it.
        if (npub != null && Nip19.normalizeNpub(npub) == null) {
            return ConfigCheck.Invalid(
                "BAD_NPUB",
                "npub must be a NIP-19 npub or a 64-character hex public key"
            )
        }
        val seenRelays = LinkedHashSet<String>()
        for (relay in relays) {
            val normalized = RelayUrl.normalizeRelay(relay)
                ?: return ConfigCheck.Invalid(
                    "BAD_RELAY",
                    "relay '${relay.take(80)}' is not a ws:// or wss:// URL"
                )
            seenRelays.add(normalized)
        }
        if (seenRelays.size > RelayUrl.MAX_RELAYS) {
            return ConfigCheck.Invalid(
                "TOO_MANY_RELAYS",
                "at most ${RelayUrl.MAX_RELAYS} relays may be configured"
            )
        }
        remoteBridge?.let { hint ->
            hint.validate()?.let { problem ->
                return ConfigCheck.Invalid("BAD_REMOTE_BRIDGE", problem)
            }
        }
        lightning.externalNode.validate()?.let { problem ->
            return ConfigCheck.Invalid("BAD_EXTERNAL_NODE", problem)
        }

        return ConfigCheck.Ok
    }

    fun toJson(): String = Json.encode(
        mapOf(
            "version" to 1,
            "enabled" to enabled,
            "bind" to bind.name,
            "port" to port,
            "auth" to auth.name,
            "passwordHash" to passwordHash,
            "keys" to keys.map { key ->
                mapOf(
                    "keyId" to key.keyId,
                    "label" to key.label,
                    "publicKey" to key.publicKey,
                    "enabled" to key.enabled
                )
            },
            "lightning" to mapOf(
                "kind" to lightning.kind.wireName,
                "baseUrl" to lightning.baseUrl,
                "timeoutMs" to lightning.timeoutMs,
                "apiKeySealed" to lightning.apiKeySealed,
                "externalNode" to lightning.externalNode.toJson()
            ),
            "acknowledgePublicBind" to acknowledgePublicBind,
            "sessionTtlMs" to sessionTtlMs,
            "maxBodyBytes" to maxBodyBytes,
            "maxConnections" to maxConnections,
            "maxSseClients" to maxSseClients,
            "npub" to npub,
            "relays" to relays,
            "remoteBridge" to remoteBridge?.let {
                mapOf("label" to it.label, "description" to it.description)
            }
        )
    )

    companion object {

        fun defaults(): SuperServerConfig = SuperServerConfig()

        fun fromJson(text: String): SuperServerConfig? {
            val root = JsonParser.parseObjectOrNull(text) ?: return null
            val defaults = defaults()
            val lightning = (root["lightning"] as? Map<*, *>).toLightning(defaults.lightning)
            return SuperServerConfig(
                enabled = root["enabled"] as? Boolean ?: defaults.enabled,
                bind = parseEnum(root["bind"] as? String, BindMode.entries, defaults.bind),
                port = intOr(root["port"], defaults.port),
                auth = parseEnum(root["auth"] as? String, AuthMode.entries, defaults.auth),
                passwordHash = (root["passwordHash"] as? String)?.takeIf { it.isNotBlank() },
                keys = parseKeys(root["keys"]),
                lightning = lightning,
                acknowledgePublicBind = root["acknowledgePublicBind"] as? Boolean
                    ?: defaults.acknowledgePublicBind,
                sessionTtlMs = longOr(root["sessionTtlMs"], defaults.sessionTtlMs),
                maxBodyBytes = intOr(root["maxBodyBytes"], defaults.maxBodyBytes),
                maxConnections = intOr(root["maxConnections"], defaults.maxConnections),
                maxSseClients = intOr(root["maxSseClients"], defaults.maxSseClients),
                npub = (root["npub"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                relays = parseRelays(root["relays"]),
                remoteBridge = RemoteBridgeHint.fromJson(root["remoteBridge"])
            )
        }

        /**
         * Keeps entries exactly as written. A malformed relay is *not* dropped
         * here: `validate` must report `BAD_RELAY` instead of silently losing the
         * operator's configuration.
         */
        private fun parseRelays(raw: Any?): List<String> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { (it as? String)?.trim()?.takeIf { value -> value.isNotEmpty() } }
        }

        private fun intOr(value: Any?, fallback: Int): Int =
            (value as? Number)?.toInt() ?: fallback

        private fun longOr(value: Any?, fallback: Long): Long =
            (value as? Number)?.toLong() ?: fallback

        private fun <T : Enum<T>> parseEnum(value: String?, values: List<T>, fallback: T): T {
            if (value == null) return fallback
            return values.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: fallback
        }

        private fun parseKeys(raw: Any?): List<ApiKeyConfig> {
            val list = raw as? List<*> ?: return emptyList()
            val keys = ArrayList<ApiKeyConfig>(list.size)
            for (item in list) {
                val map = item as? Map<*, *> ?: continue
                val publicKey = map["publicKey"] as? String ?: continue
                val explicitId = (map["keyId"] as? String)?.trim().orEmpty()
                val keyId = if (explicitId.isNotBlank()) {
                    explicitId
                } else {
                    ApiKeyConfig.deriveKeyId(publicKey) ?: continue
                }
                keys.add(
                    ApiKeyConfig(
                        keyId = keyId,
                        label = (map["label"] as? String)?.take(64) ?: "",
                        publicKey = publicKey,
                        enabled = map["enabled"] as? Boolean ?: true
                    )
                )
            }
            return keys
        }

        private fun Map<*, *>?.toLightning(fallback: LightningSettings): LightningSettings {
            if (this == null) return fallback
            return LightningSettings(
                kind = LightningKind.fromWire(this["kind"] as? String),
                baseUrl = (this["baseUrl"] as? String)?.trim() ?: "",
                timeoutMs = intOr(this["timeoutMs"], fallback.timeoutMs),
                apiKeySealed = (this["apiKeySealed"] as? String)?.takeIf { it.isNotBlank() },
                externalNode = ExternalNodeRef.fromJson(this["externalNode"]) ?: ExternalNodeRef()
            )
        }
    }
}
