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
import earth.diego.hindsight.service.RecorderBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 *     disk (see [SyncListenerService]). Re-sending a clip the phone already has is
 *     harmless — it re-acknowledges and the watch cleans up then.
 */
class ClipUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private companion object {
        const val TAG = "ClipUploadWorker"

        /** Generous: a 60-minute clip over Bluetooth Classic is minutes, not seconds. */
        const val SEND_TIMEOUT_MS = 7 * 60 * 1000L
        const val RUN_TIMEOUT_MS = 8 * 60 * 1000L
        const val ACK_GRACE_MS = 10_000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Install the fallback before sending: an OS kill/cancellation of an
        // immediate worker must not strand the outbox until the next user action.
        val retryWorker = inputData.getBoolean(ClipOutbox.IS_RETRY, false)
        ClipOutbox.refreshStatus(applicationContext)
        if (!retryWorker) ClipOutbox.enqueueRetry(applicationContext)
        val needsRetry = try {
            withTimeout(RUN_TIMEOUT_MS) {
                var nodeId: String? = null
                val channelClient = Wearable.getChannelClient(applicationContext)
                ClipOutbox.drain.drain(
                    pending = { ClipOutbox.pending(applicationContext) },
                    prepare = {
                        RecorderBus.publishSync(SyncState.Checking)
                        nodeId = findReceiverNode()
                        if (nodeId == null) RecorderBus.publishSync(SyncState.PhoneUnavailable)
                        nodeId != null
                    },
                    completed = { pending ->
                        ClipOutbox.refreshStatus(applicationContext)
                        if (!pending) RecorderBus.publishSync(SyncState.Idle)
                        else if (RecorderBus.sync.value is SyncState.Sending || RecorderBus.sync.value == SyncState.Checking) {
                            RecorderBus.publishSync(SyncState.Retry("Transfer interrupted. Retry sync."))
                        }
                    },
                ) { file ->
                    try {
                        RecorderBus.publishSync(SyncState.Sending(file.name))
                        sendOne(channelClient, checkNotNull(nodeId), file)
                        if (file.exists()) RecorderBus.publishSync(SyncState.AwaitingAck(file.name))
                        withTimeoutOrNull(ACK_GRACE_MS) {
                            RecorderBus.pendingUploads.first { !file.exists() }
                        }
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Exception) {
                        RecorderBus.publishSync(SyncState.Retry("Transfer failed. Retry sync."))
                        Log.w(TAG, "Failed to send ${file.name}", t)
                    }
                }
            }
        } catch (t: TimeoutCancellationException) {
            true // Leave margin before WorkManager's execution deadline.
        }
        if (retryWorker && needsRetry) Result.retry() else Result.success()
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

        var drainedNormally = false
        try {
            withTimeout(SEND_TIMEOUT_MS) {
                channelClient.registerChannelCallback(channel, callback).await()
                channelClient.sendFile(channel, Uri.fromFile(file)).await()
                drained.await()
                drainedNormally = true
            }
        } finally {
            // Cancellation must release transport resources too. Never close a
            // successfully sent channel early; the phone owns that final close.
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    if (!drainedNormally) runCatching { channelClient.close(channel).await() }
                    runCatching { channelClient.unregisterChannelCallback(channel, callback).await() }
                }
            }
        }
    }

    private suspend fun findReceiverNode(): String? = try {
        Wearable.getCapabilityClient(applicationContext)
            .getCapability(WearProtocol.CAPABILITY_CLIP_RECEIVER, CapabilityClient.FILTER_REACHABLE)
            .await().nodes.sortedByDescending { it.isNearby }.firstOrNull()?.id
    } catch (t: CancellationException) {
        throw t
    } catch (t: Exception) {
        Log.w(TAG, "Could not discover receiver", t)
        null
    }
}
