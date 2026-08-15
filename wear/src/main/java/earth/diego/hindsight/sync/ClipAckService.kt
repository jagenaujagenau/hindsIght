package earth.diego.hindsight.sync

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.shared.WearProtocol
import java.io.File

/**
 * The only place a saved clip is ever deleted from the watch.
 *
 * The phone sends `/clip-ack/<filename>` after it has written the clip to its own
 * storage and renamed it into place. Until that message arrives the watch keeps
 * its copy, so a transfer that dies in flight costs a retry rather than the
 * recording.
 */
class ClipAckService : WearableListenerService() {

    private companion object { const val TAG = "ClipAckService" }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val fileName = WearProtocol.fileNameFromAckPath(messageEvent.path) ?: run {
            Log.w(TAG, "Ignoring unexpected message path ${messageEvent.path}")
            return
        }

        val clip = File(ClipOutbox.directory(this), fileName)
        if (clip.exists() && clip.delete()) {
            Log.i(TAG, "Phone acknowledged $fileName; reclaimed ${clip.name}")
        } else {
            // Duplicate ack after a resend, or the user deleted it. Either is fine.
            Log.i(TAG, "Ack for $fileName with nothing to delete")
        }

        RecorderBus.publishPending(ClipOutbox.pending(this).size)
    }
}
