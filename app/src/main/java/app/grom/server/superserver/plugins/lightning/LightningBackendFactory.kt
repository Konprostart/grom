// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.lightning

import app.grom.server.superserver.LightningKind
import app.grom.server.superserver.LightningSettings

/**
 * Builds the configured Lightning adapter (D43).
 *
 * A `null` answer is not an error here: it means "no usable backend", which the
 * caller reports as `LIGHTNING_UNCONFIGURED`. The configuration validator has
 * already refused a non-https URL; this factory repeats the check rather than
 * trusting that every caller validated, and requires a decrypted API key.
 */
object LightningBackendFactory {

    fun create(
        settings: LightningSettings,
        apiKeyPlaintext: String?
    ): LightningBackend? = when (settings.kind) {
        LightningKind.NONE -> null
        LightningKind.LNBITS -> {
            if (!settings.baseUrl.startsWith("https://") || apiKeyPlaintext.isNullOrBlank()) {
                null
            } else {
                LnbitsBackend(
                    baseUrl = settings.baseUrl,
                    apiKey = apiKeyPlaintext,
                    timeoutMs = settings.timeoutMs
                )
            }
        }
    }
}
