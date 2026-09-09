package earth.diego.hindsight.ui

import earth.diego.hindsight.service.SaveState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Locale

class RecorderPresentationTest {
    private val originalLocale = Locale.getDefault()
    @Before fun locale() { Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test fun `buffer time is actual duration including partial windows`() {
        assertEquals("0:00", formatBufferDuration(-1))
        assertEquals("0:42", formatBufferDuration(42_001))
        assertEquals("1:00", formatBufferDuration(59_968))
        assertEquals("60:00", formatBufferDuration(3_600_000))
    }

    @Test fun `success distinguishes local persistence from delivery`() {
        val saved = SaveState.Saved("clip.m4a", 58_000, 100)
        assertEquals("Saved 0:58 · waiting for phone", saveMessage(saved, 1))
        assertEquals("Saved 0:58 · on phone", saveMessage(saved.copy(delivered = true), 0))
    }

    @Test fun `busy empty error and offline states have feedback`() {
        assertEquals("Saving…", saveMessage(SaveState.Saving, 0))
        assertTrue(saveMessage(SaveState.NothingBuffered, 0)!!.contains("Wait"))
        assertEquals("Free storage and retry", saveMessage(SaveState.Failed("Free storage and retry"), 0))
        assertEquals("2 waiting for phone", saveMessage(SaveState.Idle, 2))
        assertNull(saveMessage(SaveState.Idle, 0))
    }
}
