package earth.diego.hindsight.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import earth.diego.hindsight.shared.WearProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Ships pending clips to the phone over a Data Layer channel.
 *
 * Delivery is best-effort per run: anything still pending returns [Result.retry] so
 * WorkManager backs off until the phone is in range again.
 */
class ClipUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private companion object {
        const val TAG = "ClipUploadWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pending = ClipOutbox.pending(applicationContext)
        if (pending.isEmpty()) return@withContext Result.success()

        val nodeId = findReceiverNode() ?: run {
            Log.i(TAG, "No paired phone with the receiver capability; will retry")
            return@withContext Result.retry()
        }

        val channelClient = Wearable.getChannelClient(applicationContext)
        var failures = 0

        for (file in pending) {
            try {
                val channel = channelClient
                    .openChannel(nodeId, WearProtocol.clipChannelPath(file.name))
                    .await()
                try {
                    channelClient.sendFile(channel, Uri.fromFile(file)).await()
                } finally {
                    runCatching { channelClient.close(channel).await() }
                }
                // Confirmed on the wire — reclaim the watch's storage immediately.
                file.delete()
                Log.i(TAG, "Delivered ${file.name}")
            } catch (t: Throwable) {
                failures++
                Log.w(TAG, "Failed to deliver ${file.name}", t)
            }
        }

        if (failures > 0) Result.retry() else Result.success()
    }

    private suspend fun findReceiverNode(): String? = runCatching {
        Wearable.getCapabilityClient(applicationContext)
            .getCapability(WearProtocol.CAPABILITY_CLIP_RECEIVER, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
            // Prefer a directly-connected node over one reachable via the cloud.
            .sortedByDescending { it.isNearby }
            .firstOrNull()
            ?.id
    }.getOrNull()
}
