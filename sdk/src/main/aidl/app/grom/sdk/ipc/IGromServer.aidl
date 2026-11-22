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
 * agent exec, and the operator's SSH bridge (D58). API 4 adds project-scoped
 * key/value access (D61), so an embedded project can only reach its own keys.
 * API 5 adds the operator's notification settings and a test send (D65).
 * API 6 adds this device's fleet identity, mutual pairing and the paired-device
 * roster (D70-D75).
 * Every new method goes through the same per-transaction signature gate as the
 * older ones.
 */
interface IGromServer {

    /** API version; API 6 adds device identity, pairing and the fleet roster. */
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

    // ------------------------------------------------------- API 4 (D61)

    /**
     * Runs `kv.get` or `kv.put` inside [projectId]'s own namespace and returns
     * `{"status":…,"body":…}`. The engine computes the `p:<projectId>:` prefix
     * itself, so a project can never read or overwrite another project's keys.
     * A key that already carries a foreign prefix is refused with
     * `PROJECT_KEY_INVALID`; an unregistered project is refused outright.
     */
    String projectExec(String projectId, String op, String argsJson);

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

    // ------------------------------------------------------- API 5 (D65-D67)

    /**
     * `{"enabled":…,"chatId":…,"tokenSet":…,"lastDelivery":{…}}`. The bot token
     * itself is never returned by any method, only whether one is stored.
     */
    String notifySettingsJson();

    /**
     * Accepts `{enabled, chatId, token?, clearToken?}`. A null token keeps the
     * stored one; the new one is sealed before it reaches the disk.
     */
    String notifySaveSettings(String json);

    /**
     * Sends one message now and reports `{"ok":…,"code":…}`. `ok` is true only
     * when the provider answered `{"ok":true}`.
     */
    String notifyTestJson();

    // --------------------------------------------------------- API 6 (D70-D75)

    /**
     * `{nodeId, publicKey, label, fingerprint, createdAtMs}`. The private seed
     * is never returned by any method and never reaches a document (D72).
     */
    String nodeIdentityJson();

    /**
     * Replaces the identity and forgets every pairing; [confirmation] must be
     * the word `RESET`, because every peer will see a stranger (D72).
     */
    String nodeResetIdentityJson(String confirmation);

    /**
     * The paired devices with their measured state. This is the only projection
     * that carries an address; status and export never do (D59).
     */
    String peersJson();

    /** Opens a short-lived pairing session and returns the code to show. */
    String peerPairStartJson();

    /** The device waiting for this operator's decision, if any. */
    String peerPairPendingJson();

    /** Approves or rejects the waiting device: `{accept, scopes}` (D73). */
    String peerPairCompleteJson(String decisionJson);

    /**
     * Joins a device that is showing a code; blocks until the other operator
     * decides (or the request times out). The result is an *unverified* offer
     * that must be confirmed on this screen (D71).
     */
    String peerJoinJson(String requestJson);

    /** Confirms the verified fingerprint and stores the peer. */
    String peerConfirmJson(String confirmJson);

    /** Removes a paired device; its key stops verifying on the next request. */
    void peerRemove(String nodeId);

    /** Probes the paired devices now. Nothing is scheduled in the background. */
    String peerRefreshJson();
}
