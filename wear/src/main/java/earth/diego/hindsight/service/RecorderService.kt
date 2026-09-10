package earth.diego.hindsight.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.BatteryManager
import android.os.SystemClock
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import earth.diego.hindsight.MainActivity
import earth.diego.hindsight.R
import earth.diego.hindsight.audio.AtomicClip
import earth.diego.hindsight.audio.CaptureState
import earth.diego.hindsight.audio.ClipBuilder
import earth.diego.hindsight.audio.RingRecorder
import earth.diego.hindsight.data.Retention
import earth.diego.hindsight.data.RecorderSettings
import earth.diego.hindsight.data.SessionDeadline
import earth.diego.hindsight.data.SessionLimit
import earth.diego.hindsight.sync.ClipOutbox
import earth.diego.hindsight.tile.SaveTileService
import androidx.wear.tiles.TileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the microphone for as long as the user wants it. Foreground + partial
 * wake lock, because the point of the app is to still be listening after the
 * screen has gone dark and the watch has tried to doze.
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "RecorderService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        // Also spans service recreation: a new instance cannot delete the old
        // ring or open a second microphone while asynchronous teardown finishes.
        private val recorderLifecycle = Mutex()

        const val ACTION_START = "earth.diego.hindsight.START"
        const val ACTION_RESTORE = "earth.diego.hindsight.RESTORE"
        const val ACTION_STOP = "earth.diego.hindsight.STOP"
        const val ACTION_SAVE = "earth.diego.hindsight.SAVE"
        const val ACTION_SAVE_STOP = "earth.diego.hindsight.SAVE_STOP"
        const val ACTION_SET_TIMER = "earth.diego.hindsight.SET_TIMER"
        const val ACTION_SET_RETENTION = "earth.diego.hindsight.SET_RETENTION"
        const val EXTRA_MINUTES = "minutes"

        fun start(context: Context) = launch(context, ACTION_START)
        fun restore(context: Context) = launch(context, ACTION_RESTORE)

        private fun launch(context: Context, action: String) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, RecorderService::class.java).setAction(action),
                )
            } catch (t: Exception) {
                Log.e(TAG, "Could not start recording service", t)
                RecorderBus.publish(CaptureState(error = "Could not start. Open the app and check microphone access."))
            }
        }

        fun stop(context: Context) = send(context, ACTION_STOP)
        fun save(context: Context) = send(context, ACTION_SAVE)
        fun saveAndStop(context: Context) = send(context, ACTION_SAVE_STOP)
        fun setTimer(context: Context, limit: SessionLimit) =
            send(context, ACTION_SET_TIMER) { it.putExtra(EXTRA_MINUTES, limit.minutes) }

        fun setRetention(context: Context, retention: Retention) =
            send(context, ACTION_SET_RETENTION) { it.putExtra(EXTRA_MINUTES, retention.minutes) }

        private fun send(context: Context, action: String, configure: (Intent) -> Unit = {}) {
            val intent = Intent(context, RecorderService::class.java).setAction(action).also(configure)
            try {
                context.startService(intent)
            } catch (t: Exception) {
                Log.e(TAG, "Could not deliver $action", t)
                if (action == ACTION_SAVE || action == ACTION_SAVE_STOP) RecorderBus.publish(SaveState.Failed("Open the app and try saving again."))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = recorderLifecycle
    private lateinit var recorder: RingRecorder
    private lateinit var settings: RecorderSettings
    private var wakeLock: PowerManager.WakeLock? = null
    private var saveJob: Deferred<Boolean>? = null
    private var timerJob: Job? = null
    private var sessionDeadline: SessionDeadline? = null
    private var bootCount = -1
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val status = BatteryStatus((level * 100 / scale).coerceIn(0, 100),
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            val changed = RecorderBus.battery.value?.low != status.low
            RecorderBus.publishBattery(status)
            if (changed && ::recorder.isInitialized && recorder.state.value.recording) {
                updateNotification()
                updateTile()
            }
        }
    }
    private var destroyed = false
    private var initializationFailed = false
    private var latestStartId = 0
    private var retention: Retention = Retention.DEFAULT

    override fun onCreate() {
        super.onCreate()
        settings = RecorderSettings(this)
        bootCount = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        createNotificationChannel()
        scope.launch {
            lifecycle.withLock {
                try {
                    // Stale-ring cleanup performs filesystem IO, never on the main thread.
                    recorder = withContext(Dispatchers.IO) {
                        AtomicClip.discardIncomplete(ClipOutbox.directory(this@RecorderService))
                        RingRecorder(File(cacheDir, "ring"))
                    }
                    retention = settings.retention.first()
                    if (publishPending() > 0) ClipOutbox.enqueueUpload(this@RecorderService)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Exception) {
                    initializationFailed = true
                    Log.e(TAG, "Recorder initialization failed", t)
                    RecorderBus.publish(CaptureState(error = "Could not open recording storage. Free space and retry."))
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@withLock
                }
                scope.launch {
                    while (isActive) {
                        runCatching { publishPending() }.onFailure { Log.w(TAG, "Storage check failed", it) }
                        delay(30_000)
                    }
                }
                scope.launch { RecorderBus.meterSubscribers.collect { recorder.setMeteringEnabled(it > 0) } }
                scope.launch { recorder.levels.collect(RecorderBus::publishLevel) }
                scope.launch {
                    var lastMode: Pair<Boolean, Boolean>? = null
                    recorder.state.collect { state ->
                        val mode = state.recording to state.starting
                        // Do not erase a terminal error merely by creating a service for a save/stop.
                        if (state.recording || state.starting || state.error != null) {
                            RecorderBus.publish(state.copy(sessionRemainingMs = if (state.recording) remainingSessionMs() else null))
                        }
                        if (mode != lastMode) {
                            lastMode = mode
                            updateTile()
                        }
                        if (state.error != null) lifecycle.withLock {
                            if (!destroyed && recorder.state.value.error == state.error) {
                                val failedStartId = latestStartId
                                saveJob?.join()
                                timerJob?.cancel()
                                timerJob = null
                                releaseWakeLock()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                updateTile()
                                stopSelf(failedStartId)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RESTORE
        latestStartId = startId
        // Meet the foreground deadline before any settings read or queued shutdown.
        if (action == ACTION_START || action == ACTION_RESTORE) {
            try {
                check(hasMicPermission()) { "Microphone permission not granted" }
                goForeground()
            } catch (t: Exception) {
                Log.e(TAG, "Could not promote microphone service", t)
                RecorderBus.publish(CaptureState(error = "Open the app and allow microphone access, then retry."))
                releaseWakeLock()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        scope.launch {
            lifecycle.withLock {
                if (destroyed || initializationFailed) return@withLock
                try {
                    when (action) {
                        ACTION_START -> {
                            settings.setListening(true)
                            beginRecording(startId)
                        }
                        ACTION_RESTORE -> {
                            if (settings.listening.first() && RecorderBus.capture.value.error == null) beginRecording(startId, restoring = true)
                            else stopRecording(startId)
                        }
                        ACTION_STOP -> {
                            RecorderBus.publishSessionNotice(null)
                            clearObsoleteSaveError()
                            settings.setListening(false)
                            stopRecording(startId)
                        }
                        ACTION_SAVE_STOP -> {
                            if (recorder.state.value.recording) {
                                saveBeforeStop(save = { saveClip().await() }, stop = {
                                    settings.setListening(false)
                                    stopRecording(startId)
                                })
                                // Failure deliberately leaves both capture and its buffer alive.
                            } else {
                                RecorderBus.publish(SaveState.NothingBuffered)
                                if (!recorder.state.value.starting) stopSelf(startId)
                            }
                        }
                        ACTION_SET_TIMER -> {
                            val limit = SessionLimit.fromMinutes(intent?.getIntExtra(EXTRA_MINUTES, 0) ?: 0)
                            settings.setSessionLimit(limit)
                            sessionDeadline = if (recorder.state.value.recording || recorder.state.value.starting) newDeadline(limit) else null
                            settings.setSessionDeadline(sessionDeadline)
                            RecorderBus.publishSessionNotice(null)
                            armTimer()
                            if (recorder.state.value.recording) updateNotification() else if (!recorder.state.value.starting) stopSelf(startId)
                        }
                        ACTION_SAVE -> {
                            if (recorder.state.value.recording) saveClip()
                            else {
                                RecorderBus.publish(SaveState.NothingBuffered)
                                updateTile()
                                if (!recorder.state.value.starting) stopSelf(startId)
                            }
                        }
                        ACTION_SET_RETENTION -> {
                            val minutes = intent?.getIntExtra(EXTRA_MINUTES, retention.minutes) ?: retention.minutes
                            retention = Retention.fromMinutes(minutes)
                            settings.setRetention(retention)
                            recorder.setRetention(retention.minutes)
                            if (recorder.state.value.recording) updateNotification() else stopSelf(startId)
                        }
                    }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Exception) {
                    Log.e(TAG, "Recorder command failed", t)
                    stopRecording(startId)
                    RecorderBus.publish(CaptureState(error = "Could not record. Check microphone access and free storage, then retry."))
                    updateTile()
                }
            }
        }
        return if (action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    private suspend fun beginRecording(startId: Int, restoring: Boolean = false) {
        check(hasMicPermission()) { "Microphone permission not granted" }
        retention = settings.retention.first()
        if (!recorder.state.value.recording && !recorder.state.value.starting) {
            sessionDeadline = if (restoring) settings.sessionDeadline.first() else null
            if (sessionDeadline != null && remainingSessionMs() == 0L) {
                settings.setListening(false)
                stopRecording(startId)
                RecorderBus.publishSessionNotice("Timer ended while the app was away. Previously saved clips are safe.")
                return
            }
            if (sessionDeadline == null) sessionDeadline = newDeadline(settings.sessionLimit.first())
            settings.setSessionDeadline(sessionDeadline)
            val storage = withContext(Dispatchers.IO) { ClipOutbox.refreshStatus(this@RecorderService) }
            StoragePolicy.requireSpace(storage.freeBytes, retention.minutes * 180_000L + StoragePolicy.MIB)
            clearObsoleteSaveError()
            RecorderBus.publishSessionNotice(null)
        }
        goForeground()
        acquireWakeLock()
        recorder.start(retention.minutes)
        armTimer()
        updateNotification()
        updateTile()
    }

    private fun clearObsoleteSaveError() {
        if (RecorderBus.save.value is SaveState.Failed || RecorderBus.save.value == SaveState.NothingBuffered) {
            RecorderBus.publish(SaveState.Idle)
        }
    }

    private fun newDeadline(limit: SessionLimit) =
        SessionDeadline.start(limit, System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount)

    private fun remainingSessionMs() =
        sessionDeadline?.remainingMs(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount)

    private fun armTimer() {
        timerJob?.cancel()
        timerJob = null
        if (recorder.state.value.recording) RecorderBus.publish(recorder.state.value.copy(sessionRemainingMs = remainingSessionMs()))
        val expected = sessionDeadline ?: return
        timerJob = scope.launch {
            try {
                // A full ring no longer emits changing capture status; keep the
                // countdown independent, and use monotonic time while this job lives.
                val endElapsed = SystemClock.elapsedRealtime() + (remainingSessionMs() ?: return@launch)
                while (true) {
                    val remaining = (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    if (recorder.state.value.recording) RecorderBus.publish(recorder.state.value.copy(sessionRemainingMs = remaining))
                    if (remaining == 0L) break
                    delay(minOf(1_000, remaining))
                }
                lifecycle.withLock {
                    if (destroyed || sessionDeadline != expected) return@withLock
                    val expiredStartId = latestStartId
                    timerJob = null // stopRecording must not cancel this coroutine itself.
                    sessionDeadline = null
                    // Keep the expired persisted deadline until a successful stop.
                    // If the process dies after a failed save, restore must not extend the timer.
                    if (!recorder.state.value.recording) {
                        settings.setListening(false)
                        stopRecording(expiredStartId)
                        RecorderBus.publishSessionNotice("Timer ended before audio was available.")
                    } else if (saveBeforeStop(save = { saveClip().await() }, stop = {
                        settings.setListening(false)
                        stopRecording(expiredStartId)
                    })) {
                        RecorderBus.publishSessionNotice("Timer finished. Buffer saved.")
                    } else {
                        RecorderBus.publish(recorder.state.value.copy(sessionRemainingMs = null))
                        RecorderBus.publishSessionNotice("Timer save failed. Still listening—free storage or stop manually.")
                    }
                    updateTile()
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                timerJob = null
                sessionDeadline = null
                Log.e(TAG, "Session timer failed", t)
                RecorderBus.publishSessionNotice("Timer unavailable. Check listening status and stop manually.")
                RecorderBus.publish(recorder.state.value.copy(sessionRemainingMs = null))
                updateTile()
            }
        }
    }

    private suspend fun stopRecording(startId: Int) {
        timerJob?.cancel()
        timerJob = null
        sessionDeadline = null
        // Finish a locally requested save before releasing its pinned source files.
        saveJob?.join()
        if (::recorder.isInitialized) recorder.stop()
        RecorderBus.publish(CaptureState())
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateTile()
        stopSelf(startId)
    }

    private fun saveClip(): Deferred<Boolean> {
        saveJob?.takeIf { it.isActive }?.let { return it }
        RecorderBus.publish(SaveState.Saving)
        updateTile()
        val job = scope.async {
            try {
                val window = recorder.pinNewest(retention.minutes)
                if (window == null) {
                    RecorderBus.publish(SaveState.NothingBuffered)
                    return@async false
                }
                try {
                    val saved = withContext(Dispatchers.IO) {
                        val storage = ClipOutbox.refreshStatus(this@RecorderService)
                        val required = window.segments.sumOf { it.file.length() } + window.frames * 16L + 64 * 1024L
                        StoragePolicy.requireSpace(storage.freeBytes, required)
                        val output = File(ClipOutbox.directory(this@RecorderService), newClipName(window.frames))
                        ClipBuilder.build(window.segments, window.frames, output)
                        SaveState.Saved(output.name, ClipBuilder.durationMs(window.frames), output.length(), completedAtMs = System.currentTimeMillis())
                    }
                    // Publish local success before enqueueing: an unusually fast ack
                    // must not be overwritten with "waiting for phone" afterwards.
                    RecorderBus.publish(saved)
                    RecorderBus.publishSessionNotice(null)
                    // Confirmation belongs to persistence, not the tap; also works from the tile.
                    runCatching {
                        getSystemService(Vibrator::class.java)?.vibrate(
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                        )
                    }
                    // Sync scheduling is separate from local persistence: a scheduler
                    // failure must not turn safely saved audio into a "save failed" UI.
                    try {
                        settings.noteSuccessfulSave()
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Exception) {
                        Log.w(TAG, "Could not update gesture hints", t)
                    }
                    try {
                        publishPending()
                        ClipOutbox.enqueueUpload(this@RecorderService)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Exception) {
                        Log.e(TAG, "Clip saved; sync will be retried on next open/reconnect", t)
                    }
                } finally {
                    withContext(NonCancellable) { recorder.release(window) }
                }
                true
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                Log.e(TAG, "Save failed", t)
                RecorderBus.publish(SaveState.Failed(if (t is InsufficientStorage) t.message!! else "Could not save. Free storage, then tap to try again."))
                false
            } finally {
                updateTile()
            }
        }
        saveJob = job
        return job
    }

    private fun newClipName(frames: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val seconds = ClipBuilder.durationMs(frames) / 1000
        return "clip_${stamp}_${seconds}s_${java.util.UUID.randomUUID()}.m4a"
    }

    // ------------------------------------------------------------------

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * The foreground notification doubles as the watch-face presence chip.
     *
     * A recorder that keeps running after the screen dims is otherwise invisible:
     * OngoingActivity puts it on the watch face, so the user can see it is still
     * listening and get back in one tap. It decorates this very builder, so it
     * must be applied before build().
     */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val saveStop = PendingIntent.getService(
            this, 2, Intent(this, RecorderService::class.java).setAction(ACTION_SAVE_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detail = buildString {
            append(getString(R.string.notification_text, retention.label))
            if (sessionDeadline != null) append(" · timed session")
            if (RecorderBus.battery.value?.low == true) append(" · low battery")
            if (RecorderBus.storage.value?.warning != null) append(" · sync clips to free space")
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(detail)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_save, "Save & stop", saveStop)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop without saving", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_foreground)
            .setTouchIntent(open)
            .setStatus(
                Status.Builder().addTemplate(detail).build(),
            )
            .build()
            .apply(this)

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hindsight:capture")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private suspend fun publishPending(): Int {
        val previousWarning = RecorderBus.storage.value?.warning
        val status = withContext(Dispatchers.IO) { ClipOutbox.refreshStatus(this@RecorderService) }
        if (status.warning != previousWarning && ::recorder.isInitialized && recorder.state.value.recording) {
            updateNotification()
            updateTile()
        }
        return status.pendingCount
    }

    private fun updateTile() {
        runCatching { TileService.getUpdater(this).requestUpdate(SaveTileService::class.java) }
            .onFailure { Log.w(TAG, "Tile update unavailable", it) }
    }

    override fun onDestroy() {
        destroyed = true
        timerJob?.cancel()
        runCatching { unregisterReceiver(batteryReceiver) }
        // Service callbacks cannot suspend. Cleanup joins on IO while this scope
        // stays alive long enough to finish any save and release the microphone.
        scope.launch {
            lifecycle.withLock {
                saveJob?.join()
                if (::recorder.isInitialized) recorder.stop()
                if (RecorderBus.capture.value.error == null) RecorderBus.publish(CaptureState())
                releaseWakeLock()
                updateTile()
            }
            scope.cancel()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
