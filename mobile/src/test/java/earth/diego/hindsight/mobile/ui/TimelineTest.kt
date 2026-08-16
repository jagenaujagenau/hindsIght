package earth.diego.hindsight.mobile.ui

import earth.diego.hindsight.mobile.data.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

class TimelineTest {

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun clip(hour: Int, minute: Int, dayOffset: Int = 0) = Clip(
        file = File("clip_$hour$minute$dayOffset.m4a"),
        recordedAt = at(hour, minute, dayOffset),
        durationSeconds = 59,
        sizeBytes = 1000,
    )

    @Test
    fun `newest first, with a day header`() {
        val rows = Timeline.build(listOf(clip(9, 0), clip(17, 30)))

        assertTrue(rows.first() is TimelineRow.Day)
        val moments = rows.filterIsInstance<TimelineRow.Moment>()
        assertEquals(2, moments.size)
        assertTrue(moments[0].clip.recordedAt > moments[1].clip.recordedAt)
    }

    @Test
    fun `only the first clip of an hour carries the hour mark`() {
        val rows = Timeline.build(listOf(clip(14, 5), clip(14, 40)))
        val moments = rows.filterIsInstance<TimelineRow.Moment>()

        assertEquals("14", moments[0].hour)
        assertNull("second clip in the same hour repeats no label", moments[1].hour)
    }

    @Test
    fun `a long silence in the day becomes a row`() {
        val rows = Timeline.build(listOf(clip(9, 0), clip(17, 0)))
        val quiet = rows.filterIsInstance<TimelineRow.Quiet>()

        assertEquals(1, quiet.size)
        assertEquals(8, quiet.first().hours)
    }

    @Test
    fun `adjacent hours are not treated as silence`() {
        val rows = Timeline.build(listOf(clip(14, 50), clip(15, 5)))
        assertTrue(rows.filterIsInstance<TimelineRow.Quiet>().isEmpty())
    }

    @Test
    fun `silence is never drawn across a day boundary`() {
        // Late last night and early this morning is a huge gap, but overnight is
        // not something the archive should editorialise about.
        val rows = Timeline.build(listOf(clip(23, 0, dayOffset = -1), clip(8, 0)))

        assertEquals(2, rows.filterIsInstance<TimelineRow.Day>().size)
        assertTrue(rows.filterIsInstance<TimelineRow.Quiet>().isEmpty())
    }

    @Test
    fun `every row has a stable unique key`() {
        val rows = Timeline.build(listOf(clip(9, 0), clip(17, 0), clip(17, 30)))
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
