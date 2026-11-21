// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

/**
 * The sanitised projection of the notification state (D67, S5-R042).
 *
 * This is an allow-list, not a serialisation: `enabled`, `configured` and the
 * measured result of the last delivery. The chat id and the bot token are
 * deliberately absent — the first identifies the device's owner, the second is
 * the credential itself.
 *
 * `lastDelivery.ok` is `null` until an attempt actually happened, so a green
 * lamp always means "the provider accepted a message", never "it is configured".
 */
object NotifyStatus {

    fun build(
        settings: NotifySettings,
        lastDelivery: Map<String, Any?>?
    ): Map<String, Any?> = linkedMapOf(
        "enabled" to settings.enabled,
        "configured" to settings.isConfigured(),
        "lastDelivery" to linkedMapOf(
            "ok" to (lastDelivery?.get("ok") as? Boolean),
            "atMs" to ((lastDelivery?.get("atMs") as? Number)?.toLong() ?: 0L)
        )
    )

    /** The empty answer used when no notifier exists (peripheral role, tests). */
    fun empty(): Map<String, Any?> = build(NotifySettings(), null)
}
