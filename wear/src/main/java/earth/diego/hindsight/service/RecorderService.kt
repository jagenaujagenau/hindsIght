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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import earth.diego.hindsight.MainActivity
import earth.diego.hindsight.R
import earth.diego.hindsight.audio.ClipBuilder
import earth.diego.hindsight.audio.RingRecorder
import earth.diego.hindsight.data.Retention
import earth.diego.hindsight.data.RecorderSettings
import earth.diego.hindsight.sync.ClipOutbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        const val ACTION_START = "earth.diego.hindsight.START"
        const val ACTION_STOP = "earth.diego.hindsight.STOP"
        const val ACTION_SAVE = "earth.diego.hindsight.SAVE"
        const val ACTION_SET_RETENTION = "earth.diego.hindsight.SET_RETENTION"
        const val EXTRA_MINUTES = "minutes"

        /** Only START may launch the service — everything else addresses a live one. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) = send(context, ACTION_STOP)
        fun save(context: Context) = send(context, ACTION_SAVE)

        fun setRetention(context: Context, retention: Retention) =
            send(context, ACTION_SET_RETENTION) { it.putExtra(EXTRA_MINUTES, retention.minutes) }

        private fun send(context: Context, action: String, configure: (Intent) -> Unit = {}) {
            val intent = Intent(context, RecorderService::class.java).setAction(action).also(configure)
            // Deliberately startService, not startForegroundService: these actions never
            // create the service, so promising a startForeground() we would not make
            // would crash us on API 26+.
            runCatching { context.startService(intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var recorder: RingRecorder
    private lateinit var settings: RecorderSettings
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var retention: Retention = Retention.DEFAULT
    @Volatile private var saveInFlight = false
    @Volatile private var foregrounded = false

    override fun onCreate() {
        super.onCreate()
        settings = RecorderSettings(this)
        recorder = RingRecorder(File(cacheDir, "ring"))
        createNotificationChannel()

        scope.launch { recorder.state.collect(RecorderBus::publish) }
        scope.launch {
            retention = settings.retention.first()
            RecorderBus.publishPending(ClipOutbox.pending(this@RecorderService).size)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action != ACTION_START && !foregrounded) {
            // Raced with a stop, or delivered to a service we never promoted.
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START -> beginRecording()
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            ACTION_SAVE -> saveClip()
            ACTION_SET_RETENTION -> {
                val minutes = intent?.getIntExtra(EXTRA_MINUTES, retention.minutes) ?: retention.minutes
                retention = Retention.fromMinutes(minutes)
                recorder.setRetention(retention.minutes)
                scope.launch { settings.setRetention(retention) }
                if (recorder.state.value.recording) updateNotification()
            }
            else -> beginRecording()
        }
        // Restarting a dead capture beats silently having stopped listening.
        return START_STICKY
    }

    private fun beginRecording() {
        if (!hasMicPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted; refusing to start")
            stopSelf()
            return
        }
        // Promote synchronously — the 5-second startForeground deadline is not
        // something to spend on a DataStore read.
        goForeground()
        acquireWakeLock()
        scope.launch {
            // On the first start after process death, onCreate's load may still be
            // in flight; reading it here keeps us off the default 5-minute window.
            retention = settings.retention.first()
            recorder.start(retention.minutes)
            updateNotification()
        }
    }

    private fun stopRecording() {
        foregrounded = false
        recorder.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveClip() {
        if (saveInFlight) return
        saveInFlight = true
        scope.launch {
            try {
                val window = recorder.pinNewest(retention.minutes)
                if (window == null) {
                    RecorderBus.publish(SaveOutcome.NothingBuffered)
                    return@launch
                }
                try {
                    val output = File(ClipOutbox.directory(this@RecorderService), newClipName(window.frames))
                    withContext(Dispatchers.IO) {
                        ClipBuilder.build(window.segments, window.frames, output)
                    }
                    ClipOutbox.enqueueUpload(this@RecorderService)
                    RecorderBus.publishPending(ClipOutbox.pending(this@RecorderService).size)
                    RecorderBus.publish(
                        SaveOutcome.Saved(ClipBuilder.durationMs(window.frames), output.length()),
                    )
                } finally {
                    recorder.release(window)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Save failed", t)
                RecorderBus.publish(SaveOutcome.Failed(t.message ?: "Save failed"))
            } finally {
                saveInFlight = false
            }
        }
    }

    private fun newClipName(frames: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val seconds = ClipBuilder.durationMs(frames) / 1000
        return "clip_${stamp}_${seconds}s.m4a"
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
        foregrounded = true
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, retention.label))
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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

    override fun onDestroy() {
        recorder.stop()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
