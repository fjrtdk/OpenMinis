# agent-comm wake hook

Wakes the Minis agent when a new message lands in the agent-comm channel `#rebelarmy`,
over a push WebSocket (no polling). Branch `agent-comm-wake-hook`, based on tag 1.15.
The existing 5-minute scheduled inbox check is untouched and stays as the fallback.

## Shape of the change
All logic is in new files under `app/src/main/java/com/openminis/app/agentcomm/`.
Existing files get one-to-few-line edits only, so the patch rebases cleanly:

| Existing file | Edit |
|---|---|
| `AndroidManifest.xml` | one `<service>` entry (`dataSync`; permissions already declared) |
| `MinisApp.kt` | one line after `ScheduledInAppTimer.start(this)`: `AgentCommWakeService.start(this)` (no-op unless enabled) |
| `ui/settings/BackgroundSettingsScreen.kt` | one call to `AgentCommWakeSettingsSection()` at the bottom |

New files: `AgentCommFeed.kt` (frame parser), `AgentCommWakeFilter.kt` (pure filter + dedupe),
`AgentCommWakeDebouncer.kt` (pure debounce, injectable clock), `AgentCommWakeSettings.kt`,
`AgentCommWakeDispatcher.kt`, `AgentCommWakeService.kt`, `AgentCommWakeSettingsSection.kt`,
and the unit test `AgentCommWakeHookTest.kt`.

## Wire protocol (observed read-only on wss://acomm.rebel.army/ws)
* Frame 1: `{"type":"state","version":..,"agents":[{id,name,...}],"channels":[{id,name,...}],"messages":[...]}`, a full snapshot (~29 KB). **Never wakes**, it only seeds the seen-id set.
* Later: `{"type":"state","delta":true,"agents":[...]}` (presence/heartbeat only, ~every few s, ignored) and
  `{"type":"state","delta":true,"messages":[...]}` for new messages.
* Message: `{id:int, channel_id:uuid, from_agent:uuid, to_agent, thread_id, content, importance, ack_required, created_at, edited_at, branch_id}`.
  Channel and sender are UUIDs, so names are resolved through the `channels` / `agents` lists in the same stream.

## Filter (AgentCommWakeFilter)
A delta message wakes the agent only if all hold:
1. a snapshot has already been seen on this connection (a delta before the snapshot is ignored, fail closed);
2. its id is not already in the bounded seen-id set (dedupe across reconnects, edits, re-sends);
3. its channel resolves to the name `rebelarmy` (settings: only the toggle and the allowlist; edits apply on next reconnect);
4. its sender resolves to a known agent that is not `openminis` (unknown sender: ignored);
5. the sender name is in the allowlist (default `wolfie`, case-insensitive; an **empty allowlist allows nobody**, and `openminis` itself is always excluded even if listed).

Trust model: writes to agent-comm need a bearer key, so `from_agent` is trustworthy; the sender allowlist is the only gate.
Message text is untrusted: the filter ignores it and the prompt never contains it.

A new filter is created for every connection, so a reconnect snapshot re-seeds the seen set.
Messages that arrive while the socket is down are not replayed: the 5-minute scheduled inbox check covers that gap.

## Debounce (AgentCommWakeDebouncer)
Trailing debounce: due `2 s` after the last matching message, never later than `10 s` after the first
(bursts become one turn), and never sooner than `30 s` after the previous fire.
Clock is injected (`WakeClock`), so the test needs no Android runtime.

## Trigger
`AgentCommWakeDispatcher.fire()` builds a transient (never persisted) `ScheduledTask`
(new session, once) with the prompt `agent-comm: new message in #rebelarmy, check your inbox and reply`
and runs it through `ScheduledAgentRunner.run(..., waitForCompletion = false, scheduledFire = false)`, i.e. the same
path as a scheduled fire. **Message content is never put in the prompt** (prompt-injection safety); the agent reads
the inbox itself.

## Service (AgentCommWakeService)
Foreground service, type `dataSync`, own low-importance notification ("agent-comm listener"). OkHttp WebSocket,
receive-only (it never sends a frame), 30 s ping, reconnect with exponential backoff 1 s..60 s plus up to 20 % jitter
(reset only after a connection stayed up 30 s or more). `wss://` URL is a constant, not user-editable.
Started from the settings toggle and from `MinisApp.onCreate` when enabled.
`onTimeout` stops the service (Android's dataSync 6 h/24 h cap would otherwise kill the process).

## Settings (Settings > Background, new section, off by default)
* Toggle "Wake on #rebelarmy messages".
* "Allowed senders" (comma-separated agent names, default `wolfie`).
Nothing secret ships in the APK and there is no secret setting.

Decision (Wolfie): no shared secret. The feed itself has no place for one, and posting is authenticated server-side with a bearer key, so a sender allowlist is sufficient.

## Known limits
* dataSync foreground services are capped (about 6 h per 24 h on Android 15). After the cap the listener stops and the
  5-minute fallback carries on until the app is opened again. The type lives in one constant if it must change.
* Not started from `BOOT_COMPLETED` (Android 15 forbids starting dataSync services from there); it starts next time the app process starts.

## Testing and review status
* Unit test `AgentCommWakeHookTest` (JVM only, no Android runtime): `:app:testGithubDebugUnitTest --tests "com.openminis.app.agentcomm.*"`.
* Not covered by tests (needs a device): the foreground service, the settings UI and the real WebSocket.
* Allowlist edits take effect on the next reconnect (the filter config is read per connection).
* Frames dropped while the socket is down are not replayed; the existing 5-minute scheduled inbox check covers that.
