package earth.diego.hindsight.data

/** A recording timer is separate from the rolling audio retention window. */
enum class SessionLimit(val minutes: Int, val label: String) {
    OFF(0, "No timer"), FIFTEEN(15, "15 min"), THIRTY(30, "30 min"), SIXTY(60, "60 min");

    companion object {
        fun fromMinutes(minutes: Int) = entries.firstOrNull { it.minutes == minutes } ?: OFF
    }
}

/** Monotonic within a boot; wall time is only a restart-after-reboot fallback. */
data class SessionDeadline(
    val wallTimeMs: Long,
    val elapsedTimeMs: Long,
    val bootCount: Int,
    val durationMs: Long,
) {
    fun remainingMs(nowWallMs: Long, nowElapsedMs: Long, currentBootCount: Int): Long {
        val remaining = if (bootCount >= 0 && bootCount == currentBootCount) elapsedTimeMs - nowElapsedMs
            else wallTimeMs - nowWallMs
        return remaining.coerceIn(0, durationMs)
    }

    companion object {
        fun start(limit: SessionLimit, nowWallMs: Long, nowElapsedMs: Long, bootCount: Int): SessionDeadline? {
            if (limit == SessionLimit.OFF) return null
            val duration = limit.minutes * 60_000L
            return SessionDeadline(nowWallMs + duration, nowElapsedMs + duration, bootCount, duration)
        }
    }
}
