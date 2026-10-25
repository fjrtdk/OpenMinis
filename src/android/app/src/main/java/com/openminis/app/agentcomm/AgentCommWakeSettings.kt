package com.openminis.app.agentcomm

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferences for the agent-comm wake hook. Nothing secret lives here: the feed's
 * `from` field is authenticated server-side, so the trust boundary is the sender
 * allowlist.
 */
object AgentCommWakeSettings {

    /** WebSocket endpoint. `wss` only, deliberately not user-editable. */
    const val WS_URL = "wss://acomm.rebel.army/ws"

    private const val PREFS_NAME = "agent_comm_wake"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALLOWED_SENDERS = "allowed_senders"

    internal const val DEFAULT_ALLOWED_SENDERS = "wolfie"
    private const val CHANNEL = "rebelarmy"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Off by default. */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Raw comma-separated allowlist as the user typed it (default `wolfie`). */
    fun allowedSendersRaw(ctx: Context): String =
        prefs(ctx).getString(KEY_ALLOWED_SENDERS, DEFAULT_ALLOWED_SENDERS) ?: DEFAULT_ALLOWED_SENDERS

    fun setAllowedSenders(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_ALLOWED_SENDERS, raw).apply()
    }

    /** Parsed allowlist: trimmed, lower-cased, non-blank. Empty means nobody can wake. */
    fun allowedSenders(ctx: Context): Set<String> = parseSenders(allowedSendersRaw(ctx))

    internal fun parseSenders(raw: String): Set<String> =
        raw.split(',', ';', '\n').mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toSet()

    /** Filter config snapshot from the current settings. */
    fun filterConfig(ctx: Context): WakeFilterConfig =
        WakeFilterConfig(channelName = CHANNEL, allowedSenders = allowedSenders(ctx))
}
