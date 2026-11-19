// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk.ipc;

/**
 * The Grom IPC surface (S4-R011…S4-R016).
 *
 * Reaching any method requires:
 *   1. the caller holds `app.grom.sdk.ipc.BIND_GROMSERVER`
 *      (protectionLevel="signature"), and
 *   2. GromService.onBind accepted the caller's signature.
 */
interface IGromServer {

    /** API version; F1 returns 1. */
    int apiVersion();

    /** Same JSON document as GET /grom/v1/health. */
    String healthJson();

    /** Same JSON document as GET /grom/v1/status. */
    String statusJson();

    /** Newest entries, clamped to 1…200. */
    String auditTail(int maxEntries);

    /** Deliberate restart; never counts as a crash. */
    void restartAgent();

    /** Clears quarantine and crash counters. */
    void resetAgent();

    /** Issues a new bearer token and returns it to the caller once. */
    void rotateToken();
}
