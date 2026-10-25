package com.openminis.app.agentcomm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Foreground service (dataSync) that holds a receive-only WebSocket to the
 * agent-comm feed and starts an agent turn when a new, allowed message lands in
 * #rebelarmy. No polling: the server pushes a delta within about a second.
 *
 * Frames go parser -> [AgentCommWakeFilter] -> [AgentCommWakeDebouncer] -> [AgentCommWakeDispatcher].
 * A fresh filter is created for every connection, so each reconnect snapshot
 * re-seeds the seen ids and never wakes. The socket never sends a frame.
 * Logs carry only connection state and counts, never frame text or sender names.
 */
class AgentCommWakeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = WakeClock { SystemClock.elapsedRealtime() }
    private val debouncer = AgentCommWakeDebouncer(clock)
    private val rateLimiter = AgentCommWakeRateLimiter(clock)
    private val wakeLock: PowerManager.WakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minis:agentcomm-wake")
            .apply { setReferenceCounted(false) }
    }
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var connectJob: Job? = null
    private var wakeJob: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            // A redirect must never move the feed to another host or scheme.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() must be answered with startForeground() right away.
        if (!goForeground()) return START_NOT_STICKY
        if (!AgentCommWakeSettings.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Idempotent: a second start must not open a second socket.
        if (connectJob?.isActive != true) {
            connectJob = scope.launch { connectLoop() }
            wakeJob = scope.launch { wakeLoop() }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        ensureChannel(this)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("agent-comm listener")
            .setContentText("Listening to #rebelarmy")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
        return try {
            enterForeground(notification)
            true
        } catch (t: Throwable) {
            AppLogger.error(TAG, "startForeground refused: ${t.javaClass.simpleName}")
            // Answer startForegroundService() with a bare notification first, and only then stop.
            // Never stopSelf() before a startForeground() call.
            try {
                enterForeground(
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle("agent-comm listener")
                        .build(),
                )
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "startForeground retry refused: ${t2.javaClass.simpleName}")
            }
            stopSelf()
            false
        }
    }

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_TYPE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Connect, wait for the socket to end, back off, repeat, until the service is destroyed. */
    private suspend fun connectLoop() {
        var backoffMs = BASE_BACKOFF_MS
        while (scope.isActive) {
            val ended = CompletableDeferred<Unit>()
            val snapshotSeen = CompletableDeferred<Unit>()
            var dropped = 0
            val filter = AgentCommWakeFilter(AgentCommWakeSettings.filterConfig(this))
            val openedAt = SystemClock.elapsedRealtime()
            val socket = client.newWebSocket(
                Request.Builder().url(AgentCommWakeSettings.WS_URL).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        AppLogger.info(TAG, "connected")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val frame = AgentCommFrameParser.parse(text)
                        if (frame == null) {
                            // Oversize, unparseable or not a state frame. Counts only, never content.
                            dropped++
                            if (dropped == 1 || dropped % 10 == 0) {
                                AppLogger.warning(TAG, "dropped frames: $dropped (last length=${text.length})")
                            }
                            return
                        }
                        if (!frame.isDelta) snapshotSeen.complete(Unit)
                        val count = filter.onFrame(frame)
                        if (count > 0) {
                            wakeLock.acquire(WAKE_LOCK_MS)
                            debouncer.offer(count)
                            signal.trySend(Unit)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        ended.complete(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        AppLogger.info(TAG, "closed code=$code")
                        ended.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        AppLogger.warning(TAG, "socket failure: ${t.javaClass.simpleName}")
                        ended.complete(Unit)
                    }
                },
            )
            // No snapshot soon after connecting means a stuck or wrong feed: reconnect.
            val watchdog = scope.launch {
                if (withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { snapshotSeen.await() } == null) {
                    AppLogger.warning(TAG, "no snapshot within ${SNAPSHOT_TIMEOUT_MS / 1000}s, reconnecting")
                    ended.complete(Unit)
                }
            }
            try {
                ended.await()
            } finally {
                // Also runs on cancellation (service destroyed): never leak the socket.
                watchdog.cancel()
                socket.cancel()
                if (dropped > 0) AppLogger.info(TAG, "connection ended, dropped frames: $dropped")
            }
            // Back off from 1 s, doubling to 60 s; only a connection that stayed up
            // for a while resets it, so a server that accepts and drops cannot spin us.
            if (SystemClock.elapsedRealtime() - openedAt >= STABLE_MS) backoffMs = BASE_BACKOFF_MS
            val wait = backoffMs + (backoffMs * JITTER * Random.nextDouble()).toLong()
            AppLogger.info(TAG, "reconnect in ${wait}ms")
            delay(wait)
            backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    /** Sleeps until the debouncer says a wake is due (or a new offer re-times it), then fires. */
    private suspend fun wakeLoop() {
        while (scope.isActive) {
            val due = debouncer.msUntilDue()
            if (due == null) {
                signal.receive()
            } else if (due > 0) {
                withTimeoutOrNull(due) { signal.receive() }
            } else if (debouncer.takeIfDue()) {
                if (!rateLimiter.tryAcquire()) {
                    AppLogger.warning(TAG, "wake skipped: hourly cap of ${AgentCommWakeRateLimiter.DEFAULT_MAX_PER_HOUR} reached")
                    continue
                }
                wakeLock.acquire(WAKE_LOCK_MS)
                try {
                    fireWithOneRetry { AgentCommWakeDispatcher.fire(applicationContext) }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }

    /** Android 15 caps dataSync at ~6 h/24 h; overrunning the grace period kills the whole process. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warning(TAG, "foreground time limit reached, stopping (5-minute scheduled check still runs)")
        try {
            ensureChannel(this)
            getSystemService(NotificationManager::class.java).notify(
                TIMEOUT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("agent-comm listener stopped")
                    .setContentText("Android's background time limit was reached. Open Minis to restart it.")
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "could not post stop notice: ${t.javaClass.simpleName}")
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        debouncer.clear()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentCommWake"
        private const val CHANNEL_ID = "agent_comm_wake"
        private const val NOTIFICATION_ID = 4301
        private const val TIMEOUT_NOTIFICATION_ID = 4302
        private const val WAKE_LOCK_MS = 20_000L
        private const val SNAPSHOT_TIMEOUT_MS = 30_000L
        /** One place to change the type if the dataSync cap becomes a problem. */
        private const val FOREGROUND_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val STABLE_MS = 30_000L
        private const val JITTER = 0.2

        /** Starts the listener if enabled in settings. Never throws (background starts can be refused). */
        fun start(context: Context) {
            if (!AgentCommWakeSettings.isEnabled(context)) return
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, AgentCommWakeService::class.java),
                )
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "could not start: ${t.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, AgentCommWakeService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "agent-comm listener", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
