// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk.ipc;

/**
 * The Grom IPC surface (S4-R011…S4-R016, S4 §8).
 *
 * Reaching any method requires:
 *   1. the caller holds `app.grom.sdk.ipc.BIND_GROMSERVER`
 *      (protectionLevel="signature"), and
 *   2. GromService accepted the caller's signature.
 *
 * API 2 added the public-data-plane reload (D39). API 3 adds the embeddable
 * surface (D55): project registration, the sanitised config export, a direct
 * agent exec, and the operator's SSH bridge (D58). Every new method goes
 * through the same per-transaction signature gate as the older ones.
 */
interface IGromServer {

    /** API version; API 3 is the embeddable surface. */
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

    /**
     * Re-reads the persisted public-access configuration and rebuilds only that
     * listener. Added in API 2 (D39); the control plane and agent are untouched.
     */
    void reloadSuperServer();

    // ------------------------------------------------------------- API 3 (D55)

    /** Registered host projects plus their derived liveness state. */
    String projectsJson();

    /** Registers or refreshes one project; returns the stored record. */
    String announceProject(String json);

    /** Removes a project registration. */
    void removeProject(String id);

    /** The sanitised hand-off document; contains no credential (D53). */
    String configExportJson();

    /**
     * Runs a whitelisted agent op and returns `{"status":…,"body":…}`. Failures
     * are data, never manufactured success.
     */
    String agentExec(String op, String argsJson);

    // ------------------------------------------------------------- SSH bridge

    /** Targets (with the operator's own host/user) and their measured state. */
    String sshStatusJson();

    /** One-time Ed25519 key generation; the private text must be saved at once. */
    String sshGenerateKeyJson();

    /** Creates or updates a target; secrets are sealed before the write. */
    String sshSaveTarget(String draftJson);

    /** Deletes a target and closes its tunnel. */
    void sshDeleteTarget(String id);

    /** Connects (blocking up to ~30 s); returns the measured result. */
    String sshConnect(String id, String trustFingerprint);

    /** Closes a tunnel deliberately. */
    void sshDisconnect(String id);

    /** Forgets the TOFU pin after an intentional host key change. */
    void sshClearPin(String id);

    /**
     * Runs the Lightning adapter's health op now. This is the only way the
     * dashboard's Lightning lamp can become green (D52).
     */
    String lightningProbeJson();
}
