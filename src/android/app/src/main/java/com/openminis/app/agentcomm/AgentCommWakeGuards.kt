package com.openminis.app.agentcomm

import kotlinx.coroutines.delay

/**
 * Loop cap: at most [maxPerHour] wakes in any rolling hour. A message loop between
 * two agents therefore costs at most this many turns an hour. Not thread-safe;
 * used from the single wake loop.
 */
class AgentCommWakeRateLimiter(
    private val clock: WakeClock,
    private val maxPerHour: Int = DEFAULT_MAX_PER_HOUR,
) {
    private val fires = ArrayDeque<Long>()

    /** Records a wake and returns true if one is allowed now, else false (nothing recorded). */
    fun tryAcquire(): Boolean {
        val now = clock.nowMs()
        while (fires.isNotEmpty() && now - fires.first() >= HOUR_MS) fires.removeFirst()
        if (fires.size >= maxPerHour) return false
        fires.addLast(now)
        return true
    }

    companion object {
        const val DEFAULT_MAX_PER_HOUR = 12
        const val HOUR_MS = 3_600_000L
    }
}

/** Fires once; if that returns null, waits [retryDelayMs] and fires exactly one more time. */
suspend fun fireWithOneRetry(
    retryDelayMs: Long = 10_000L,
    sleep: suspend (Long) -> Unit = { delay(it) },
    fire: suspend () -> String?,
): String? {
    fire()?.let { return it }
    sleep(retryDelayMs)
    return fire()
}
