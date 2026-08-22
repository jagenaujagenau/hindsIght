package earth.diego.hindsight.service

import earth.diego.hindsight.audio.CaptureState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SaveOutcome {
    data class Saved(val durationMs: Long, val sizeBytes: Long) : SaveOutcome
    data object NothingBuffered : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/**
 * Process-wide state channel between [RecorderService] and the UI.
 *
 * A plain object rather than a bound service: the activity comes and goes with the
 * screen on a watch, and rebinding on every wrist-raise costs more than it buys.
 */
object RecorderBus {

    private val _capture = MutableStateFlow(CaptureState())
    val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    /**
     * How many collectors are on [capture] right now.
     *
     * The recorder uses this to decide whether the waveform is worth capturing at
     * capture rate. On a watch the answer is "no" for almost the whole day: the
     * activity is stopped the moment the wrist drops, `collectAsStateWithLifecycle`
     * unsubscribes, and this falls to zero. Snapshot readers like the tile do not
     * collect and so do not count — they only need [capture]`.value` to be roughly
     * current, which it stays.
     */
    val captureCollectors: StateFlow<Int> = _capture.subscriptionCount

    private val _saves = MutableSharedFlow<SaveOutcome>(extraBufferCapacity = 4)
    val saves: SharedFlow<SaveOutcome> = _saves.asSharedFlow()

    private val _pendingUploads = MutableStateFlow(0)
    val pendingUploads: StateFlow<Int> = _pendingUploads.asStateFlow()

    internal fun publish(state: CaptureState) { _capture.value = state }
    internal fun publish(outcome: SaveOutcome) { _saves.tryEmit(outcome) }
    internal fun publishPending(count: Int) { _pendingUploads.value = count }
}
