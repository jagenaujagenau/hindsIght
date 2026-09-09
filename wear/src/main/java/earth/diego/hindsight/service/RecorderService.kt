package earth.diego.hindsight.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
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
import earth.diego.hindsight.sync.ClipOutbox
import earth.diego.hindsight.tile.SaveTileService
import androidx.wear.tiles.TileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

        fun setRetention(context: Context, retention: Retention) =
            send(context, ACTION_SET_RETENTION) { it.putExtra(EXTRA_MINUTES, retention.minutes) }

        private fun send(context: Context, action: String, configure: (Intent) -> Unit = {}) {
            val intent = Intent(context, RecorderService::class.java).setAction(action).also(configure)
            try {
                context.startService(intent)
            } catch (t: Exception) {
                Log.e(TAG, "Could not deliver $action", t)
                if (action == ACTION_SAVE) RecorderBus.publish(SaveState.Failed("Open the app and try saving again."))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = recorderLifecycle
    private lateinit var recorder: RingRecorder
    private lateinit var settings: RecorderSettings
    private var wakeLock: PowerManager.WakeLock? = null
    private var saveJob: Job? = null
    private var destroyed = false
    private var initializationFailed = false
    private var latestStartId = 0
    private var retention: Retention = Retention.DEFAULT

    override fun onCreate() {
        super.onCreate()
        settings = RecorderSettings(this)
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
                scope.launch { RecorderBus.meterSubscribers.collect { recorder.setMeteringEnabled(it > 0) } }
                scope.launch { recorder.levels.collect(RecorderBus::publishLevel) }
                scope.launch {
                    var lastMode: Pair<Boolean, Boolean>? = null
                    recorder.state.collect { state ->
                        val mode = state.recording to state.starting
                        // Do not erase a terminal error merely by creating a service for a save/stop.
                        if (state.recording || state.starting || state.error != null) RecorderBus.publish(state)
                        if (mode != lastMode) {
                            lastMode = mode
                            updateTile()
                        }
                        if (state.error != null) lifecycle.withLock {
                            if (!destroyed && recorder.state.value.error == state.error) {
                                saveJob?.join()
                                releaseWakeLock()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                updateTile()
                                stopSelf(latestStartId)
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
                            beginRecording()
                        }
                        ACTION_RESTORE -> {
                            if (settings.listening.first() && RecorderBus.capture.value.error == null) beginRecording()
                            else stopRecording(startId)
                        }
                        ACTION_STOP -> {
                            settings.setListening(false)
                            stopRecording(startId)
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

    private suspend fun beginRecording() {
        check(hasMicPermission()) { "Microphone permission not granted" }
        retention = settings.retention.first()
        goForeground()
        acquireWakeLock()
        recorder.start(retention.minutes)
        updateNotification()
        updateTile()
    }

    private suspend fun stopRecording(startId: Int) {
        // Finish a locally requested save before releasing its pinned source files.
        saveJob?.join()
        if (::recorder.isInitialized) recorder.stop()
        RecorderBus.publish(CaptureState())
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateTile()
        stopSelf(startId)
    }

    private fun saveClip() {
        if (saveJob?.isActive == true) return
        RecorderBus.publish(SaveState.Saving)
        updateTile()
        saveJob = scope.launch {
            try {
                val window = recorder.pinNewest(retention.minutes)
                if (window == null) {
                    RecorderBus.publish(SaveState.NothingBuffered)
                    return@launch
                }
                try {
                    val saved = withContext(Dispatchers.IO) {
                        val output = File(ClipOutbox.directory(this@RecorderService), newClipName(window.frames))
                        ClipBuilder.build(window.segments, window.frames, output)
                        SaveState.Saved(output.name, ClipBuilder.durationMs(window.frames), output.length())
                    }
                    // Publish local success before enqueueing: an unusually fast ack
                    // must not be overwritten with "waiting for phone" afterwards.
                    RecorderBus.publish(saved)
                    // Confirmation belongs to persistence, not the tap; also works from the tile.
                    runCatching {
                        getSystemService(Vibrator::class.java)?.vibrate(
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                        )
                    }
                    // Sync scheduling is separate from local persistence: a scheduler
                    // failure must not turn safely saved audio into a "save failed" UI.
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
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                Log.e(TAG, "Save failed", t)
                RecorderBus.publish(SaveState.Failed("Could not save. Free storage, then tap to try again."))
            } finally {
                updateTile()
            }
        }
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, retention.label))
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_foreground)
            .setTouchIntent(open)
            .setStatus(
                Status.Builder().addTemplate(getString(R.string.notification_text, retention.label)).build(),
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
        val count = withContext(Dispatchers.IO) { ClipOutbox.pending(this@RecorderService).size }
        RecorderBus.publishPending(count)
        return count
    }

    private fun updateTile() {
        runCatching { TileService.getUpdater(this).requestUpdate(SaveTileService::class.java) }
            .onFailure { Log.w(TAG, "Tile update unavailable", it) }
    }

    override fun onDestroy() {
        destroyed = true
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
