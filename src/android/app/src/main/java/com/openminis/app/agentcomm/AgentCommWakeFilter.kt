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
    private val channelIdToName = mutableMapOf<String, String>()

    /** Agent ids allowed to wake, fixed by the FIRST snapshot. Later renames cannot add or remove ids. */
    private val pinnedAllowedIds = HashSet<String>()
    private val pinnedSelfIds = HashSet<String>()
    private val seenIds = LinkedHashSet<Long>()

    /**
     * Processes [frame] and returns the number of new matching messages that
     * should wake the agent. A return value of 0 means no wake is needed.
     */
    @Synchronized
    fun onFrame(frame: FeedFrame): Int {
        if (!frame.isState) return 0

        if (!frame.isDelta) {
            // Snapshot: never wakes, only seeds seen ids. Only the FIRST snapshot pins
            // identities; a later one cannot change who is trusted (the filter is
            // per-connection, so a reconnect starts again from a fresh first snapshot).
            if (!snapshotReceived) pinIdentities(frame.agents)
            for (c in frame.channels) channelIdToName.putIfAbsent(c.id, c.name)
            for (m in frame.messages) addSeen(m.id)
            snapshotReceived = true
            return 0
        }

        // Delta before any snapshot → fail closed.
        if (!snapshotReceived) return 0

        // Deltas only add unknown channels. They never rename a known channel, never
        // change agent identities and never add trusted ids (that needs a reconnect).
        for (c in frame.channels) channelIdToName.putIfAbsent(c.id, c.name)

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
        pinnedAllowedIds.clear()
        pinnedSelfIds.clear()
        channelIdToName.clear()
        seenIds.clear()
    }

    private fun matches(msg: FeedMessage): Boolean {
        // Channel check
        val chanName = channelIdToName[msg.channelId] ?: return false
        if (!chanName.equals(config.channelName, ignoreCase = true)) return false

        // Sender check: by pinned id, not by the current (renamable) name.
        if (msg.fromAgent in pinnedSelfIds) return false
        if (msg.fromAgent !in pinnedAllowedIds) return false

        return true
    }

    /**
     * Trust is decided once, by id. A name shared by two agents (compared
     * case-insensitively) is ambiguous and trusts neither, so nobody can
     * impersonate an allowed sender with a look-alike name.
     */
    private fun pinIdentities(agents: List<FeedAgent>) {
        val byName = agents.groupBy { it.name.trim().lowercase() }
        for ((name, group) in byName) {
            if (name == config.selfAgentName.trim().lowercase()) {
                group.forEach { pinnedSelfIds.add(it.id) }
            } else if (name in allowedSenders && group.size == 1) {
                pinnedAllowedIds.add(group[0].id)
            }
        }
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
