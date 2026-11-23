// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

/**
 * Supervisor state machine (D12, D13, S3-R017…S3-R024, S3-R032).
 *
 * Pure Kotlin, no Android and no clock of its own: every time value is injected,
 * so crash windows and backoff are deterministic under test (D30).
 */
enum class AgentState {
    STOPPED,
    STARTING,
    READY,
    BACKOFF,
    QUARANTINED,
    MISSING_RUNTIME
}

data class SupervisorConfig(
    val startTimeoutMs: Long = 10_000,
    val crashWindowMs: Long = 10 * 60_000,
    val crashThreshold: Int = 3,
    val backoffScheduleMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000),
    val healthyResetMs: Long = 60_000
)

class SupervisorPolicy(
    val config: SupervisorConfig = SupervisorConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    var state: AgentState = AgentState.STOPPED
        private set

    var restarts: Int = 0
        private set

    var lastCrashAt: Long? = null
        private set

    var quarantineReason: String? = null
        private set

    private var readyAt: Long = 0L
    private var startedAt: Long = 0L
    private val crashes = ArrayList<Long>()

    fun crashCount(): Int = crashes.size

    fun isQuarantined(): Boolean = state == AgentState.QUARANTINED

    /** A start was requested. Returns the state to act on right now. */
    fun startRequested(runtimePresent: Boolean): AgentState {
        if (state == AgentState.QUARANTINED) return state
        if (!runtimePresent) {
            state = AgentState.MISSING_RUNTIME
            return state
        }
        restarts += 1
        startedAt = clock()
        state = AgentState.STARTING
        return state
    }

    /** The child printed a matching `GROM_READY <pid>` line. */
    fun onReady(): AgentState {
        if (state != AgentState.STARTING && state != AgentState.BACKOFF) {
            // A late ready line must not resurrect a stopped or quarantined agent.
            return state
        }
        state = AgentState.READY
        readyAt = clock()
        return state
    }

    /**
     * Any exit, timeout or pid mismatch before/after readiness (S3-R017).
     * Returns the state the caller must act on: BACKOFF → schedule a restart,
     * QUARANTINED → do nothing.
     */
    fun onCrash(cause: String): AgentState {
        val t = clock()
        crashes.add(t)
        prune(t)
        lastCrashAt = t
        state = if (crashes.size >= config.crashThreshold) {
            quarantineReason = cause
            AgentState.QUARANTINED
        } else {
            AgentState.BACKOFF
        }
        return state
    }

    /** A deliberate fatal from the agent — quarantines without consuming retries. */
    fun onFatal(message: String): AgentState {
        quarantineReason = "fatal: $message"
        state = AgentState.QUARANTINED
        return state
    }

    /** Periodic tick while READY. A long healthy run forgives one old crash. */
    fun onHealthyTick() {
        if (state != AgentState.READY) return
        if (crashes.isEmpty()) return
        if (clock() - readyAt >= config.healthyResetMs) {
            crashes.removeAt(0)
            readyAt = clock()
        }
    }

    fun onStopped() {
        state = AgentState.STOPPED
    }

    fun onMissingRuntime() {
        state = AgentState.MISSING_RUNTIME
    }

    /** Explicit human reset (S3-R021). Clears quarantine and the crash window. */
    fun reset() {
        crashes.clear()
        quarantineReason = null
        lastCrashAt = null
        state = AgentState.STOPPED
    }

    fun backoffMs(): Long {
        if (config.backoffScheduleMs.isEmpty()) return 0L
        val index = (crashes.size - 1).coerceIn(0, config.backoffScheduleMs.size - 1)
        return config.backoffScheduleMs[index]
    }

    fun startTimedOut(): Boolean =
        state == AgentState.STARTING && (clock() - startedAt) >= config.startTimeoutMs

    private fun prune(t: Long) {
        val cutoff = t - config.crashWindowMs
        while (crashes.isNotEmpty() && crashes[0] < cutoff) {
            crashes.removeAt(0)
        }
    }
}
