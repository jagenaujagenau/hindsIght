package earth.diego.hindsight.ui

import org.junit.Assert.*
import org.junit.Test

class WaveformMotionTest {
    private fun active() = WaveformMotion(5).apply { reset(recording = true) }
    private fun WaveformMotion.fill(level: Float) { repeat(sampleCount) { push(level) } }

    @Test fun `new samples interpolate instead of jumping`() {
        val motion = active()
        motion.push(1f)
        assertEquals(0f, motion.levels.last(), 0f)
        assertTrue(motion.needsFrame)
        assertTrue(motion.advance(1f / 60f))
        assertTrue(motion.levels.last() > 0f)
        assertTrue(motion.levels.last() < 1f)
    }

    @Test fun `attack is quicker than release`() {
        val motion = active()
        motion.fill(1f)
        motion.advance(0.035f)
        val attackDistance = motion.levels.last()
        motion.advance(0f, durationScale = 0f)
        motion.fill(0f)
        motion.advance(0.035f)
        val releaseDistance = 1f - motion.levels.last()
        assertTrue(attackDistance > releaseDistance)
    }

    @Test fun `retargeting continues from the displayed shape without overshoot`() {
        val motion = active()
        motion.fill(1f)
        motion.advance(0.035f)
        val before = motion.levels.last()
        motion.fill(0f)
        assertEquals(before, motion.levels.last(), 0f)
        motion.advance(0.016f)
        assertTrue(motion.levels.last() in 0f..before)
        val falling = motion.levels.last()
        motion.fill(1f)
        assertEquals(falling, motion.levels.last(), 0f)
        motion.advance(0.016f)
        assertTrue(motion.levels.last() in falling..1f)
    }

    @Test fun `response is consistent across display refresh rates`() {
        fun after(fps: Int): Float = active().apply {
            fill(1f)
            repeat(fps / 5) { advance(1f / fps) }
        }.levels.last()
        assertEquals(after(30), after(60), 0.00001f)
        assertEquals(after(60), after(120), 0.00001f)
    }

    @Test fun `stop settles exactly and stops requesting frames`() {
        val motion = active()
        motion.fill(1f)
        motion.advance(0f, durationScale = 0f)
        motion.setRecording(false)
        assertEquals(1f, motion.activity, 0f) // No snap on the state change.
        repeat(120) { motion.advance(1f / 60f) }
        assertEquals(0f, motion.activity, 0f)
        assertTrue(motion.levels.all { it == 0f })
        assertFalse(motion.needsFrame)
        motion.push(1f) // Late samples after Stop cannot revive the animation.
        assertFalse(motion.advance(1f / 60f))
    }

    @Test fun `silence flushes history and becomes completely still`() {
        val motion = active()
        motion.fill(0.8f)
        motion.advance(0f, durationScale = 0f)
        motion.fill(0f)
        repeat(120) { motion.advance(1f / 60f) }
        assertTrue(motion.levels.all { it == 0f })
        assertEquals(1f, motion.activity, 0f)
        assertFalse(motion.needsFrame)
        assertFalse(motion.advance(1f / 60f))
    }

    @Test fun `returning to the page clears old audio without an entrance animation`() {
        val motion = active()
        motion.fill(1f)
        motion.advance(0f, durationScale = 0f)
        motion.reset(recording = true)
        assertTrue(motion.levels.all { it == 0f })
        assertEquals(1f, motion.activity, 0f)
        assertFalse(motion.needsFrame)
    }

    @Test fun `disabled system animations snap to real audio targets`() {
        val motion = active()
        motion.push(0.7f)
        assertTrue(motion.advance(0f, durationScale = 0f))
        assertEquals(0.7f, motion.levels.last(), 0f)
        assertFalse(motion.needsFrame)
        motion.setRecording(false)
        motion.advance(0f, durationScale = 0f)
        assertEquals(0f, motion.activity, 0f)
        assertFalse(motion.needsFrame)
    }

    @Test fun `system duration scale adjusts interpolation speed`() {
        val regular = active().apply { fill(1f); advance(0.03f) }
        val slower = active().apply { fill(1f); advance(0.06f, durationScale = 2f) }
        assertEquals(regular.levels.last(), slower.levels.last(), 0.00001f)
    }

    @Test fun `invalid and out of range samples cannot produce invalid geometry`() {
        val motion = active()
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 4f, 0.5f).forEach(motion::push)
        motion.advance(0f, durationScale = 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0.5f), motion.levels, 0f)
    }
}
