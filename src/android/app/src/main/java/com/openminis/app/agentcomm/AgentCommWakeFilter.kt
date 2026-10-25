package com.openminis.app.agentcomm

/**
 * Configuration for [AgentCommWakeFilter].
 *
 * @param channelName Target channel name (case-insensitive match).
 * @param selfAgentName Own agent name; messages from self are never wake candidates.
 * @param allowedSenders Agent names permitted to trigger a wake (compared
 *   case-insensitively). An EMPTY set allows nobody (fail closed). The feed's
 *   `from` is authenticated server-side (writes need a bearer key), so the sender
 *   name is the trust boundary.
 * @param maxSeenIds Maximum number of message IDs retained for deduplication.
 */
data class WakeFilterConfig(
    val channelName: String = "rebelarmy",
    val selfAgentName: String = "openminis",
    val allowedSenders: Set<String> = setOf("wolfie"),
    val maxSeenIds: Int = 2000
)

/**
 * Evaluates [FeedFrame]s and determines how many new messages should wake the agent.
 *
 * The first frame processed on a fresh (or [reset]) filter **must** be a snapshot
 * (`isDelta == false`). If a delta arrives before any snapshot has been seen, it is
 * silently ignored and returns 0 (fail-closed).
 *
 * Thread-safe: all public methods are `@Synchronized`.
 */
class AgentCommWakeFilter(private val config: WakeFilterConfig) {

    private val allowedSenders: Set<String> = config.allowedSenders.mapTo(HashSet()) { it.trim().lowercase() }
    private var snapshotReceived = false
    private val agentIdToName = mutableMapOf<String, String>()
    private val channelIdToName = mutableMapOf<String, String>()
    private val seenIds = LinkedHashSet<Long>()

    /**
     * Processes [frame] and returns the number of new matching messages that
     * should wake the agent. A return value of 0 means no wake is needed.
     */
    @Synchronized
    fun onFrame(frame: FeedFrame): Int {
        if (!frame.isState) return 0

        if (!frame.isDelta) {
            // Snapshot: replace maps, seed seen-ids, never wake.
            agentIdToName.clear()
            channelIdToName.clear()
            for (a in frame.agents) agentIdToName[a.id] = a.name
            for (c in frame.channels) channelIdToName[c.id] = c.name
            for (m in frame.messages) addSeen(m.id)
            snapshotReceived = true
            return 0
        }

        // Delta before any snapshot → fail closed.
        if (!snapshotReceived) return 0

        // Update maps from delta (merge, not replace).
        for (a in frame.agents) agentIdToName[a.id] = a.name
        for (c in frame.channels) channelIdToName[c.id] = c.name

        var wakeCount = 0
        for (m in frame.messages) {
            if (seenIds.contains(m.id)) continue
            addSeen(m.id)
            if (matches(m)) wakeCount++
        }
        return wakeCount
    }

    /** Clears all seen IDs and lookup maps. Call when configuration changes. */
    @Synchronized
    fun reset() {
        snapshotReceived = false
        agentIdToName.clear()
        channelIdToName.clear()
        seenIds.clear()
    }

    private fun matches(msg: FeedMessage): Boolean {
        // Channel check
        val chanName = channelIdToName[msg.channelId] ?: return false
        if (!chanName.equals(config.channelName, ignoreCase = true)) return false

        // Sender check
        val senderName = agentIdToName[msg.fromAgent] ?: return false
        if (senderName.equals(config.selfAgentName, ignoreCase = true)) return false

        // Allowlist: empty allows nobody. Self is excluded above even if listed.
        if (senderName.lowercase() !in allowedSenders) return false

        return true
    }

    private fun addSeen(id: Long) {
        if (seenIds.size >= config.maxSeenIds) {
            val iter = seenIds.iterator()
            if (iter.hasNext()) {
                iter.next()
                iter.remove()
            }
        }
        seenIds.add(id)
    }
}
