package earth.diego.hindsight.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import earth.diego.hindsight.shared.WearProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Ships pending clips to the phone over a Data Layer channel.
 *
 * Two rules here were learned the hard way, and both must hold or the user loses
 * a recording:
 *
 *  1. **Never close the channel yourself right after [ChannelClient.sendFile].**
 *     That task resolves once the bytes reach the local Bluetooth buffer, long
 *     before the peer has them. Closing at that moment aborts the transfer, and
 *     the phone never even sees a channel-open event. `sendFile` closes the
 *     output stream itself; we only wait for `onOutputClosed` to confirm it
 *     drained.
 *
 *  2. **Never delete a clip here.** Transport success is not delivery. The file
 *     stays in the outbox until the phone acknowledges it has written the clip to
 *     disk (see [ClipAckService]). Re-sending a clip the phone already has is
 *     harmless — it re-acknowledges and the watch cleans up then.
 */
class ClipUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private companion object {
        const val TAG = "ClipUploadWorker"

        /** Generous: a 60-minute clip over Bluetooth Classic is minutes, not seconds. */
        const val SEND_TIMEOUT_MS = 10 * 60 * 1000L
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
            if (!file.exists()) continue // acknowledged by the phone mid-run
            try {
                sendOne(channelClient, nodeId, file)
                Log.i(TAG, "Sent ${file.name}; awaiting phone acknowledgement")
            } catch (t: Throwable) {
                failures++
                Log.w(TAG, "Failed to send ${file.name}", t)
            }
        }

        // Always retry: even a clean send is unfinished until the ack lands and
        // removes the file. The next run sees an empty outbox and succeeds.
        if (failures > 0 || ClipOutbox.pending(applicationContext).isNotEmpty()) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private suspend fun sendOne(channelClient: ChannelClient, nodeId: String, file: File) {
        val channel = channelClient
            .openChannel(nodeId, WearProtocol.clipChannelPath(file.name))
            .await()

        val drained = CompletableDeferred<Unit>()
        val callback = object : ChannelClient.ChannelCallback() {
            override fun onOutputClosed(
                closed: ChannelClient.Channel,
                closeReason: Int,
                appErrorCode: Int,
            ) {
                if (closed.path != channel.path) return
                if (closeReason == CLOSE_REASON_NORMAL) {
                    drained.complete(Unit)
                } else {
                    drained.completeExceptionally(
                        IllegalStateException("Channel closed early: reason=$closeReason error=$appErrorCode"),
                    )
                }
            }

            override fun onChannelClosed(
                closed: ChannelClient.Channel,
                closeReason: Int,
                appErrorCode: Int,
            ) {
                if (closed.path != channel.path) return
                // A close without a prior onOutputClosed means the peer went away.
                drained.completeExceptionally(
                    IllegalStateException("Channel closed before draining: reason=$closeReason"),
                )
            }
        }

        channelClient.registerChannelCallback(channel, callback).await()
        try {
            channelClient.sendFile(channel, Uri.fromFile(file)).await()
            withTimeout(SEND_TIMEOUT_MS) { drained.await() }
        } catch (t: TimeoutCancellationException) {
            runCatching { channelClient.close(channel).await() }
            throw IllegalStateException("Timed out draining ${file.name}", t)
        } finally {
            runCatching { channelClient.unregisterChannelCallback(channel, callback).await() }
        }
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
