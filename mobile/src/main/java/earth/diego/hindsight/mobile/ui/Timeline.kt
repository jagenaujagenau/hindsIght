package earth.diego.hindsight.mobile.ui

import earth.diego.hindsight.mobile.data.Clip
import earth.diego.hindsight.mobile.data.ClipStore
import java.util.Calendar

/**
 * One row of the archive.
 *
 * The library is a time axis, not a list, so the rows include the shape of the
 * day as well as its contents: which hour we are in, and where nothing was
 * captured at all.
 */
sealed interface TimelineRow {
    val key: String

    data class Day(val label: String, val dayStart: Long) : TimelineRow {
        override val key get() = "day-$dayStart"
    }

    /** A clip hanging off the axis. [hour] is set only on the first of its hour. */
    data class Moment(val clip: Clip, val hour: String?) : TimelineRow {
        override val key get() = "clip-${clip.id}"
    }

    /**
     * A stretch of the day with nothing in it. Rendering silence is the point:
     * the archive is a record of a day, and most of a day is quiet.
     */
    data class Quiet(val hours: Int, val afterClipId: String) : TimelineRow {
        override val key get() = "quiet-$afterClipId"
    }
}

object Timeline {

    /** Below this a gap is just the ordinary space between two recordings. */
    private const val MIN_QUIET_HOURS = 2

    /**
     * Lays clips out newest first, marking hour changes and the quiet stretches
     * between them. Gaps are only drawn inside a day — the hours between going to
     * bed and waking up are not interesting, and would dwarf everything else.
     */
    fun build(clips: List<Clip>): List<TimelineRow> {
        val rows = mutableListOf<TimelineRow>()
        var currentDay: Long? = null
        var previousHour: Int? = null

        clips.sortedByDescending { it.recordedAt }.forEach { clip ->
            val day = startOfDay(clip.recordedAt)
            val hour = hourOf(clip.recordedAt)

            if (day != currentDay) {
                rows += TimelineRow.Day(ClipStore.dayLabel(day), day)
                currentDay = day
                previousHour = null
            } else {
                // Newest first, so the previous row is the *later* hour.
                val gap = (previousHour ?: hour) - hour
                if (gap >= MIN_QUIET_HOURS) {
                    val previous = rows.lastOrNull { it is TimelineRow.Moment } as? TimelineRow.Moment
                    if (previous != null) rows += TimelineRow.Quiet(gap, previous.clip.id)
                }
            }

            rows += TimelineRow.Moment(
                clip = clip,
                hour = if (hour != previousHour) "%02d".format(hour) else null,
            )
            previousHour = hour
        }

        return rows
    }

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
