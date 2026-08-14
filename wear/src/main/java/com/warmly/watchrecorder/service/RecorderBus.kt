package com.warmly.watchrecorder.service

import com.warmly.watchrecorder.audio.CaptureState
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

    private val _saves = MutableSharedFlow<SaveOutcome>(extraBufferCapacity = 4)
    val saves: SharedFlow<SaveOutcome> = _saves.asSharedFlow()

    private val _pendingUploads = MutableStateFlow(0)
    val pendingUploads: StateFlow<Int> = _pendingUploads.asStateFlow()

    internal fun publish(state: CaptureState) { _capture.value = state }
    internal fun publish(outcome: SaveOutcome) { _saves.tryEmit(outcome) }
    internal fun publishPending(count: Int) { _pendingUploads.value = count }
}
