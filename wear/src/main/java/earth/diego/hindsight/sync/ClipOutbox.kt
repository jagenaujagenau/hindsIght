package earth.diego.hindsight.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Clips waiting to reach the phone. The watch is the temporary holder, never the
 * archive: a clip is deleted the moment the Data Layer confirms delivery.
 */
object ClipOutbox {

    private const val WAKE_WORK = "clip-upload-wakeup"
    private const val RETRY_WORK = "clip-upload-retry"
    internal const val IS_RETRY = "is_retry"
    internal val drain = UploadDrain()

    fun directory(context: Context): File =
        File(context.filesDir, "outbox").apply { mkdirs() }

    fun pending(context: Context): List<File> =
        directory(context).listFiles { f -> f.isFile && f.extension == "m4a" }
            ?.sortedBy { it.name }
            .orEmpty()

    fun enqueueUpload(context: Context) {
        // Wakeups never return Result.retry(), so reconnects cannot sit behind
        // hours of backoff. Append preserves a wakeup arriving as a drain exits;
        // each drain also picks up new clips, leaving queued wakeups as cheap no-ops.
        val request = OneTimeWorkRequestBuilder<ClipUploadWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WAKE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    internal fun enqueueRetry(context: Context) {
        val request = OneTimeWorkRequestBuilder<ClipUploadWorker>()
            .setInputData(workDataOf(IS_RETRY to true))
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Only one delayed fallback, independent of immediate reconnect/manual saves.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.KEEP, request)
    }
}
