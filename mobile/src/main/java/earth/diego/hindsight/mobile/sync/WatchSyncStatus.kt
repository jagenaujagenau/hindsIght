package earth.diego.hindsight.mobile.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Carries the watch's reply back to whatever is showing the refresh indicator.
 *
 * The reply arrives on a Play services callback in a different component, so it
 * cannot simply be returned from the request.
 */
object WatchSyncStatus {
    private val _pending = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val pending: SharedFlow<Int> = _pending.asSharedFlow()

    internal fun report(count: Int) { _pending.tryEmit(count) }
}
