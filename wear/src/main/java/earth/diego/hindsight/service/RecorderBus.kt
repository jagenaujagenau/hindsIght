package earth.diego.hindsight.service

import earth.diego.hindsight.audio.CaptureState
import earth.diego.hindsight.sync.SyncState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Saved(
        val fileName: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val delivered: Boolean = false,
        val completedAtMs: Long = 0L,
    ) : SaveState
    data object NothingBuffered : SaveState
    data class Failed(val message: String) : SaveState
}

/** Process-wide status survives activity/tile changes; metering is ephemeral. */
object RecorderBus {
    private val _capture = MutableStateFlow(CaptureState())
    val capture = _capture.asStateFlow()

    // A StateFlow, not a replay-less event: tile saves and results while the
    // activity is stopped must still be visible on the next wrist raise.
    private val _save = MutableStateFlow<SaveState>(SaveState.Idle)
    val save = _save.asStateFlow()

    private val _pendingUploads = MutableStateFlow(0)
    val pendingUploads = _pendingUploads.asStateFlow()

    private val _storage = MutableStateFlow<StorageStatus?>(null)
    val storage = _storage.asStateFlow()
    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    val battery = _battery.asStateFlow()
    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync = _sync.asStateFlow()
    private val _sessionNotice = MutableStateFlow<String?>(null)
    val sessionNotice = _sessionNotice.asStateFlow()

    internal fun publishStorage(status: StorageStatus) {
        _storage.value = status
        publishPending(status.pendingCount)
    }
    internal fun publishBattery(status: BatteryStatus) { _battery.value = status }
    internal fun publishSync(status: SyncState) { _sync.value = status }
    internal fun publishSessionNotice(message: String?) { _sessionNotice.value = message }
    internal fun queueSync() {
        _sync.update { if (it is SyncState.Sending || it is SyncState.AwaitingAck || it == SyncState.Checking) it else SyncState.Queued }
    }
    internal fun acknowledgeSync(fileName: String, pendingCount: Int) {
        _sync.update { current ->
            if ((current is SyncState.Sending && current.fileName == fileName) ||
                (current is SyncState.AwaitingAck && current.fileName == fileName)) {
                if (pendingCount == 0) SyncState.Idle else SyncState.Queued
            } else current
        }
    }

    private val _levels = MutableSharedFlow<Float>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val levels = _levels.asSharedFlow()
    internal val meterSubscribers = _levels.subscriptionCount

    internal fun publish(state: CaptureState) { _capture.value = state }
    internal fun publish(state: SaveState) { _save.value = state }
    internal fun publishLevel(peak: Float) { _levels.tryEmit(peak) }
    internal fun publishPending(count: Int) { _pendingUploads.value = count }
    internal fun acknowledge(fileName: String) {
        _save.update { state ->
            if (state is SaveState.Saved && state.fileName == fileName) state.copy(delivered = true) else state
        }
    }
}
