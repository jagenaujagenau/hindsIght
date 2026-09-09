package earth.diego.hindsight.sync

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.tile.SaveTileService
import androidx.wear.tiles.TileService
import earth.diego.hindsight.shared.WearProtocol
import java.io.File

/**
 * The watch's side of syncing: acknowledgements, sync requests, and noticing
 * when the phone comes back.
 */
class SyncListenerService : WearableListenerService() {

    private companion object { const val TAG = "SyncListener" }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when {
            messageEvent.path == WearProtocol.MESSAGE_SYNC_REQUEST -> onSyncRequested(messageEvent)
            else -> WearProtocol.fileNameFromAckPath(messageEvent.path)?.let(::onAcknowledged)
                ?: Log.w(TAG, "Ignoring unexpected message path ${messageEvent.path}")
        }
    }

    /**
     * The only place a saved clip is ever deleted from the watch: the phone has
     * written it to its own storage and renamed it into place.
     */
    private fun onAcknowledged(fileName: String) {
        val clip = File(ClipOutbox.directory(this), fileName)
        if (clip.exists() && clip.delete()) {
            Log.i(TAG, "Phone acknowledged $fileName; reclaimed it")
        } else {
            // Duplicate ack after a resend, or already gone. Either is fine.
            Log.i(TAG, "Ack for $fileName with nothing to delete")
        }
        RecorderBus.acknowledge(fileName)
        RecorderBus.publishPending(ClipOutbox.pending(this).size)
        TileService.getUpdater(this).requestUpdate(SaveTileService::class.java)
    }

    private fun onSyncRequested(messageEvent: MessageEvent) {
        val pending = ClipOutbox.pending(this).size
        Log.i(TAG, "Phone asked to sync; $pending clip(s) pending")
        if (pending > 0) ClipOutbox.enqueueUpload(this)
        RecorderBus.publishPending(pending)
        TileService.getUpdater(this).requestUpdate(SaveTileService::class.java)

        // Tell the phone what it is waiting for, so it can say something true
        // instead of spinning.
        runCatching {
            Wearable.getMessageClient(this).sendMessage(
                messageEvent.sourceNodeId,
                WearProtocol.MESSAGE_SYNC_STATUS,
                pending.toString().toByteArray(),
            )
        }
    }

    /**
     * Fires when the phone becomes reachable again.
     *
     * Without this a clip saved out of range waits on WorkManager's exponential
     * backoff, which caps at five hours — so the recording could sit on the watch
     * long after the phone was back. Retrying on reconnect is the fix; the manual
     * pull on the phone is only a backstop.
     */
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.name != WearProtocol.CAPABILITY_CLIP_RECEIVER) return
        val reachable = capabilityInfo.nodes.any { it.isNearby }
        val pending = ClipOutbox.pending(this).size
        Log.i(TAG, "Receiver capability changed: reachable=$reachable pending=$pending")
        if (reachable && pending > 0) ClipOutbox.enqueueUpload(this)
    }
}
