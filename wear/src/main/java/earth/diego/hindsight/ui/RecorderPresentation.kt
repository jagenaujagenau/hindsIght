package earth.diego.hindsight.ui

import earth.diego.hindsight.service.SaveState
import earth.diego.hindsight.sync.SyncState
import java.util.Locale

internal fun formatBufferDuration(millis: Long): String {
    // AAC trimming is in 64 ms frames: a full minute is 59.968 s. Round to
    // display precision so a full buffer does not appear permanently one second short.
    val seconds = (millis.coerceAtLeast(0) + 500) / 1000
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}

internal const val SAVE_CONFIRMATION_MS = 5_000L

internal fun confirmationRemainingMs(saved: SaveState.Saved, nowMs: Long): Long =
    (SAVE_CONFIRMATION_MS - (nowMs - saved.completedAtMs)).coerceIn(0, SAVE_CONFIRMATION_MS)

internal fun formatStorageBytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

internal fun syncMessage(sync: SyncState, pending: Int): String? {
    if (pending == 0) return null
    val status = when (sync) {
        SyncState.Idle, SyncState.Queued -> "queued"
        SyncState.Checking -> "finding phone…"
        SyncState.PhoneUnavailable -> "phone unavailable"
        is SyncState.Sending -> "sending…"
        is SyncState.AwaitingAck -> "awaiting confirmation"
        is SyncState.Retry -> "retry needed"
    }
    return "$pending pending · $status"
}

internal fun saveMessage(save: SaveState, pendingUploads: Int): String? = when (save) {
    SaveState.Idle -> if (pendingUploads > 0) "$pendingUploads waiting for phone" else null
    SaveState.Saving -> "Saving…"
    is SaveState.Saved -> "Saved ${formatBufferDuration(save.durationMs)} · " +
        if (save.delivered) "on phone" else "waiting for phone"
    SaveState.NothingBuffered -> "Nothing buffered yet. Wait a moment, then tap to save."
    is SaveState.Failed -> save.message
}
