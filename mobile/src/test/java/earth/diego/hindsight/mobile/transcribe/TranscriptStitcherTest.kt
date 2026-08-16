package earth.diego.hindsight.mobile.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptStitcherTest {

    @Test
    fun `joins chunks that do not overlap`() {
        val text = TranscriptStitcher.stitch(listOf("hello there", "general kenobi"))
        assertEquals("hello there general kenobi", text)
    }

    @Test
    fun `ignores blank chunks from silence`() {
        val text = TranscriptStitcher.stitch(listOf("", "hello there", "", "general kenobi", ""))
        assertEquals("hello there general kenobi", text)
    }

    /** The case observed on a real recording, where neither side contains the other. */
    @Test
    fun `splices a seam the two chunks disagree about`() {
        val first = "we talked about the schedule for a while " +
            "and mentioned the second half of the plan clearly"
        val second = "Mentioning the second half of the plan properly and then moving on"

        val text = TranscriptStitcher.append(first, second)

        // The shared run appears exactly once, and the trailing mis-hearing of it
        // ("get known") is gone.
        assertEquals(1, Regex("the second half of the plan").findAll(text).count())
        assertFalse(text.contains("get known"))
        assertTrue(text.startsWith("we talked about the schedule"))
        assertTrue(text.endsWith("phase one was a success"))
    }

    @Test
    fun `drops a chunk that repeats the previous one entirely`() {
        val first = "the quick brown fox jumps over the lazy dog"
        val second = "quick brown fox jumps over"

        assertEquals(first, TranscriptStitcher.append(first, second))
    }

    @Test
    fun `keeps both when a short word coincides`() {
        // "the" alone is below the minimum run, so this must not be treated as a seam.
        val text = TranscriptStitcher.append("something about the cat", "the dog was barking")
        assertEquals("something about the cat the dog was barking", text)
    }

    @Test
    fun `matches across differing case and punctuation`() {
        val text = TranscriptStitcher.append(
            "we talked about the budget review meeting",
            "Budget review meeting, and then lunch",
        )
        assertEquals(1, Regex("(?i)budget review meeting").findAll(text).count())
        assertTrue(text.endsWith("and then lunch"))
    }

    @Test
    fun `a repeat far from the seam is not mistaken for an overlap`() {
        val first = "the second half of the plan " + (1..40).joinToString(" ") { "filler$it" }
        val second = "completely different words here"

        val text = TranscriptStitcher.append(first, second)
        assertTrue(text.endsWith("completely different words here"))
        assertTrue(text.startsWith("the second half of the plan"))
    }
}
