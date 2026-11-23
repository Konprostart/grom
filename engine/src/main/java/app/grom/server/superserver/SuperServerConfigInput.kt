// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

/**
 * The one way a configuration document becomes a saved configuration (D86).
 *
 * The standalone screen and an embedding host must not have two opinions about
 * what a valid data plane looks like. Both paths therefore go through
 * [SuperServerConfigInput.parse] — the screen calls it through
 * `SuperServerConfig.validate`, the host through
 * `IGromServer.superSaveConfig` — so the same document is accepted or refused
 * with the same code, and a refusal never half-applies.
 *
 * This type is pure: no Android, no file, no socket. The store and the listener
 * are the caller's job.
 */
sealed class ConfigParse {

    data class Parsed(val config: SuperServerConfig) : ConfigParse()

    /** A stable code the caller can map onto its own UI (S5-R052). */
    data class Refused(val code: String, val message: String) : ConfigParse()
}

object SuperServerConfigInput {

    /** The same cap as the store, applied before anything is written. */
    const val MAX_BYTES: Int = 64 * 1024

    const val BAD_CONFIG: String = "BAD_CONFIG"

    /**
     * Parses and fully validates [raw] against [controlPort].
     *
     * Validation is the *whole* [SuperServerConfig.validate] contract, not a
     * subset: an off-loopback bind without a credential, an unacknowledged
     * public bind, a port equal to the control plane's, a non-https Lightning
     * backend and every key shape are refused here exactly as they are on the
     * screen. Accepting a document the UI would refuse would be a second,
     * weaker policy — which is the failure mode this function exists to prevent.
     */
    fun parse(raw: String?, controlPort: Int): ConfigParse {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return ConfigParse.Refused(BAD_CONFIG, "the configuration document is empty")
        }
        if (text.length > MAX_BYTES) {
            return ConfigParse.Refused(
                BAD_CONFIG,
                "the configuration document is larger than $MAX_BYTES bytes"
            )
        }
        val config = SuperServerConfig.fromJson(text)
            ?: return ConfigParse.Refused(BAD_CONFIG, "the configuration is not a JSON object")
        return when (val check = config.validate(controlPort)) {
            is ConfigCheck.Invalid -> ConfigParse.Refused(check.code, check.message)
            ConfigCheck.Ok -> ConfigParse.Parsed(config)
        }
    }
}
