package earth.diego.hindsight.service

/** A failed or cancelled save must never reach the destructive stop operation. */
internal suspend fun saveBeforeStop(save: suspend () -> Boolean, stop: suspend () -> Unit): Boolean {
    if (!save()) return false
    stop()
    return true
}
