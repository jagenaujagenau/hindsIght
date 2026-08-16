package earth.diego.hindsight.mobile.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import earth.diego.hindsight.mobile.MainActivity
import earth.diego.hindsight.mobile.R
import earth.diego.hindsight.mobile.audio.WaveformWorker
import earth.diego.hindsight.mobile.data.ClipStore
import earth.diego.hindsight.shared.WearProtocol
import com.google.android.gms.tasks.Tasks
import java.io.File

/**
 * Receives clips pushed by the watch over a Data Layer channel.
 *
 * Callbacks arrive on a background thread supplied by Play services, so the copy
 * runs inline — the watch's worker only deletes its copy once this side has
 * drained the stream, which makes an interrupted transfer safe to retry.
 */
class ClipReceiverService : WearableListenerService() {

    private companion object {
        const val TAG = "ClipReceiver"
        const val CHANNEL_ID = "clips"
        const val COPY_BUFFER = 32 * 1024
    }

    /** The watch's answer to a sync request: how many clips it still holds. */
    override fun onMessageReceived(messageEvent: com.google.android.gms.wearable.MessageEvent) {
        if (messageEvent.path != WearProtocol.MESSAGE_SYNC_STATUS) return
        val pending = String(messageEvent.data).toIntOrNull() ?: return
        Log.i(TAG, "Watch reports $pending clip(s) pending")
        WatchSyncStatus.report(pending)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val fileName = WearProtocol.fileNameFromChannelPath(channel.path) ?: run {
            Log.w(TAG, "Ignoring unexpected channel path ${channel.path}")
            return
        }

        val channelClient = Wearable.getChannelClient(applicationContext)
        val destination = File(ClipStore.directory(this), fileName)
        // Write to a temp name so a half-received clip never shows up in the list.
        val partial = File(destination.parentFile, "$fileName.part")

        try {
            val input = Tasks.await(channelClient.getInputStream(channel))
            input.use { source ->
                partial.outputStream().use { sink -> source.copyTo(sink, COPY_BUFFER) }
            }
            check(partial.length() > 0) { "Received an empty clip" }
            check(partial.renameTo(destination)) { "Could not finalise ${destination.name}" }

            ClipStore.refresh(this)
            // Build the envelope now so opening the clip later is instant.
            WaveformWorker.enqueue(this)
            notifyArrival(fileName)
            Log.i(TAG, "Received $fileName (${destination.length()} bytes)")

            // Only now is it safe for the watch to drop its copy. Sent after the
            // rename, so an ack can never describe a partial file.
            acknowledge(channel.nodeId, fileName)
        } catch (t: Throwable) {
            partial.delete()
            // No ack: the watch keeps the clip and the next run retries it.
            Log.e(TAG, "Failed to receive $fileName", t)
        } finally {
            runCatching { Tasks.await(channelClient.close(channel)) }
        }
    }

    private fun acknowledge(nodeId: String, fileName: String) {
        try {
            Tasks.await(
                Wearable.getMessageClient(applicationContext).sendMessage(
                    nodeId,
                    WearProtocol.ackMessagePath(fileName),
                    ByteArray(0),
                ),
            )
            Log.i(TAG, "Acknowledged $fileName to $nodeId")
        } catch (t: Throwable) {
            // The clip is safe here; the watch simply resends until an ack lands.
            Log.w(TAG, "Could not acknowledge $fileName", t)
        }
    }

    private fun notifyArrival(fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.clip_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        manager.notify(
            fileName.hashCode(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("New clip from your watch")
                .setContentText(fileName)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
