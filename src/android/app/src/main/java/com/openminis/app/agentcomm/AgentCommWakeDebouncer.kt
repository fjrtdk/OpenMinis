package com.openminis.app.agentcomm

/** Abstraction over system clock for testability. */
fun interface WakeClock {
    fun nowMs(): Long
}

/**
 * Debounces wake signals so that bursts of messages produce a single wake.
 *
 * - [quietMs]: silence window after the last [offer] before a wake is due.
 * - [maxWaitMs]: maximum wall-clock time from the first pending offer before
 *   forcing a wake, even if offers keep arriving.
 * - [minIntervalMs]: minimum gap between consecutive fires.
 *
 * Thread-safe: all public methods are `@Synchronized`.
 */
class AgentCommWakeDebouncer(
    private val clock: WakeClock,
    val quietMs: Long = 2_000L,
    val maxWaitMs: Long = 10_000L,
    val minIntervalMs: Long = 30_000L
) {
    private var firstPendingAt: Long? = null
    private var lastPendingAt: Long? = null
    private var lastFiredAt: Long? = null

    /** Records a pending wake. Ignored when [count] <= 0. */
    @Synchronized
    fun offer(count: Int = 1) {
        if (count <= 0) return
        val now = clock.nowMs()
        if (firstPendingAt == null) firstPendingAt = now
        lastPendingAt = now
    }

    /**
     * Milliseconds until the pending wake is due, or `null` if nothing is pending.
     * Returns a value >= 0 (clamped at zero).
     */
    @Synchronized
    fun msUntilDue(): Long? {
        val first = firstPendingAt ?: return null
        val last = lastPendingAt ?: return null
        val now = clock.nowMs()

        val quietTarget = last + quietMs
        val maxTarget = first + maxWaitMs
        var dueAt = minOf(quietTarget, maxTarget)

        lastFiredAt?.let { fired ->
            dueAt = maxOf(dueAt, fired + minIntervalMs)
        }

        return maxOf(0L, dueAt - now)
    }

    /**
     * Fires the pending wake if it is due. Returns `true` and clears pending
     * state when fired; `false` otherwise.
     */
    @Synchronized
    fun takeIfDue(): Boolean {
        if (firstPendingAt == null) return false
        if (msUntilDue() != 0L) return false
        firstPendingAt = null
        lastPendingAt = null
        lastFiredAt = clock.nowMs()
        return true
    }

    /** Drops any pending wake without resetting [lastFiredAt]. */
    @Synchronized
    fun clear() {
        firstPendingAt = null
        lastPendingAt = null
    }
}
