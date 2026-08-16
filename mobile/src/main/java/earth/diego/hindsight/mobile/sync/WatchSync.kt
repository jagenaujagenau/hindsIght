package earth.diego.hindsight.mobile.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import earth.diego.hindsight.shared.WearProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface SyncOutcome {
    /** The watch was asked; [pending] is what it reported it still holds. */
    data class Asked(val pending: Int?) : SyncOutcome
    data object NoWatch : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Asks the watch to send anything it is still holding.
 *
 * The phone cannot pull clips — the watch pushes them — so a refresh gesture here
 * is a request, not a fetch. It exists because a clip saved out of range waits on
 * the watch's exponential backoff, which caps at five hours; this collapses that
 * wait to now.
 */
object WatchSync {

    private const val TAG = "WatchSync"

    suspend fun requestSync(context: Context): SyncOutcome = withContext(Dispatchers.IO) {
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return@withContext SyncOutcome.NoWatch

            var asked = false
            nodes.forEach { node ->
                runCatching {
                    Wearable.getMessageClient(context).sendMessage(
                        node.id,
                        WearProtocol.MESSAGE_SYNC_REQUEST,
                        ByteArray(0),
                    ).await()
                    asked = true
                }.onFailure { Log.w(TAG, "Could not reach ${node.displayName}", it) }
            }

            if (asked) SyncOutcome.Asked(null) else SyncOutcome.NoWatch
        } catch (t: Throwable) {
            Log.w(TAG, "Sync request failed", t)
            SyncOutcome.Failed(t.message ?: "Could not reach the watch")
        }
    }
}
