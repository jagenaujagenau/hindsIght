package earth.diego.hindsight.mobile.audio

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import earth.diego.hindsight.mobile.data.ClipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds missing waveform sidecars in the background.
 *
 * Runs after a clip lands so that opening it is instant. Also sweeps any clip
 * without a sidecar, which covers recordings that arrived before this existed and
 * decodes that were interrupted.
 */
class WaveformWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WaveformWorker"
        private const val UNIQUE_WORK = "waveform-scan"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WaveformWorker>().build(),
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val pending = ClipStore.directory(applicationContext)
            .listFiles { f -> f.isFile && f.extension == "m4a" }
            .orEmpty()
            .filter { !Waveform.sidecarFor(it).exists() }

        if (pending.isEmpty()) return@withContext Result.success()

        var built = 0
        for (clip in pending) {
            if (isStopped) break
            if (Waveform.generate(clip)) built++
        }
        Log.i(TAG, "Built $built of ${pending.size} waveforms")

        ClipStore.refresh(applicationContext)
        Result.success()
    }
}
