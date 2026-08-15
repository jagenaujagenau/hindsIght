package earth.diego.hindsight.mobile.transcribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import earth.diego.hindsight.mobile.data.ClipStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Runs transcription for one clip.
 *
 * WorkManager rather than an app-scoped coroutine because this is long — roughly
 * 40 minutes of work for an hour of audio — and must survive the user leaving
 * the screen or the process being killed.
 */
class TranscribeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_CLIP = "clip"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "transcribing"
        private const val NOTIFICATION_ID = 4242

        private fun workName(clipId: String) = "transcribe-$clipId"

        fun start(context: Context, clipId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(clipId),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TranscribeWorker>()
                    .setInputData(workDataOf(KEY_CLIP to clipId))
                    // The user asked for this and is waiting on it, so it should not
                    // sit behind Doze deferral like ordinary background work.
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }

        fun cancel(context: Context, clipId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(clipId))
        }

        fun observe(context: Context, clipId: String): Flow<WorkInfo?> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(workName(clipId))
                .map { infos -> infos.firstOrNull() }
    }

    /**
     * Runs in the foreground, with a notification.
     *
     * Not for show: measured on a Pixel 10, plain background work stalls forever
     * because the platform drops the recognition service connection once the
     * device dozes ("Connection to speech recognition service lost"). Foreground
     * work keeps it alive, and an hour of audio is long enough that the user
     * deserves to see progress anyway.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcribing", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Transcribing clip")
            .setContentText(if (total > 0) "Part $done of $total" else "Preparing…")
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val clipId = inputData.getString(KEY_CLIP) ?: return Result.failure()
        val clip = File(ClipStore.directory(applicationContext), clipId)
        if (!clip.exists()) return Result.failure()

        setForeground(foregroundInfo(0, 0))
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to 0))

        return when (val outcome = Transcriber.transcribe(applicationContext, clip) { done, total ->
            // Fire-and-forget progress; a dropped update only costs a stale bar.
            runCatching {
                setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                setForegroundAsync(foregroundInfo(done, total))
            }
        }) {
            is TranscriptionResult.Success -> {
                if (outcome.text.isBlank()) {
                    Result.failure(workDataOf(KEY_ERROR to "No speech was recognised in this clip"))
                } else {
                    ClipStore.saveTranscript(applicationContext, clip, outcome.text)
                    Result.success()
                }
            }

            is TranscriptionResult.Unavailable ->
                Result.failure(workDataOf(KEY_ERROR to outcome.reason))

            is TranscriptionResult.Failed ->
                Result.failure(workDataOf(KEY_ERROR to outcome.reason))
        }
    }
}
