package earth.diego.hindsight.sync

/** Transport progress is distinct from the phone's durable acknowledgement. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Queued : SyncState
    data object Checking : SyncState
    data object PhoneUnavailable : SyncState
    data class Sending(val fileName: String) : SyncState
    data class AwaitingAck(val fileName: String) : SyncState
    data class Retry(val message: String) : SyncState
}
