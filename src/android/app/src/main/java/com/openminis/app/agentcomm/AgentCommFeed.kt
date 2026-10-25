package com.openminis.app.agentcomm

import org.json.JSONObject

/** Parsed agent from the feed. */
data class FeedAgent(val id: String, val name: String)

/** Parsed channel from the feed. */
data class FeedChannel(val id: String, val name: String)

/** Parsed message from the feed. */
data class FeedMessage(
    val id: Long,
    val channelId: String,
    val fromAgent: String,
    val content: String
)

/** A single parsed WebSocket frame from the agent-comm feed. */
data class FeedFrame(
    val isState: Boolean,
    val isDelta: Boolean,
    val agents: List<FeedAgent>,
    val channels: List<FeedChannel>,
    val messages: List<FeedMessage>
)

/**
 * Parses raw JSON text frames from the agent-comm WebSocket feed.
 *
 * Returns `null` for non-JSON input, oversized frames, or frames whose
 * `"type"` field is not `"state"`. Individual malformed entries within a
 * valid frame are silently skipped rather than causing the entire parse to fail.
 */
object AgentCommFrameParser {

    /** Maximum accepted frame length in characters. */
    const val MAX_FRAME_CHARS = 4_000_000

    /**
     * Parses [text] into a [FeedFrame], or returns `null` if the input is
     * invalid, oversized, or not a state frame.
     */
    fun parse(text: String): FeedFrame? {
        if (text.length > MAX_FRAME_CHARS) return null

        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }

        if (json.optString("type") != "state") return null

        val isDelta = json.optBoolean("delta", false)

        val agents = mutableListOf<FeedAgent>()
        json.optJSONArray("agents")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isEmpty() || name.isEmpty()) continue
                agents += FeedAgent(id, name)
            }
        }

        val channels = mutableListOf<FeedChannel>()
        json.optJSONArray("channels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isEmpty() || name.isEmpty()) continue
                channels += FeedChannel(id, name)
            }
        }

        val messages = mutableListOf<FeedMessage>()
        json.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = try {
                    obj.getLong("id")
                } catch (_: Exception) {
                    continue
                }
                val channelId = obj.optString("channel_id")
                val fromAgent = obj.optString("from_agent")
                val rawContent = obj.opt("content")
                val content = if (rawContent is String) rawContent else ""
                messages += FeedMessage(id, channelId, fromAgent, content)
            }
        }

        return FeedFrame(
            isState = true,
            isDelta = isDelta,
            agents = agents,
            channels = channels,
            messages = messages
        )
    }
}
