package com.openminis.app.agentcomm

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask

/**
 * Starts an agent turn through the same path as a scheduled fire
 * ([ScheduledAgentRunner.run]) using a transient, never persisted [ScheduledTask].
 *
 * The prompt is a fixed string. Message text from the feed is untrusted and is
 * NEVER put into the prompt; the agent reads its inbox itself.
 */
object AgentCommWakeDispatcher {

    private const val TAG = "AgentCommWake"

    const val WAKE_PROMPT = "agent-comm: new message in #rebelarmy, check your inbox and reply"

    /** Returns the session id the turn was dispatched to, or null if it could not start. */
    suspend fun fire(context: Context): String? {
        val task = ScheduledTask(
            label = "agent-comm wake",
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = WAKE_PROMPT,
            targetMode = ScheduledTargetMode.NewSession,
        )
        return try {
            ScheduledAgentRunner.run(
                context = context.applicationContext,
                task = task,
                waitForCompletion = false,
                scheduledFire = false,
            ).also { AppLogger.info(TAG, if (it != null) "wake dispatched" else "wake not started") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "wake dispatch failed: ${t.javaClass.simpleName}")
            null
        }
    }
}
