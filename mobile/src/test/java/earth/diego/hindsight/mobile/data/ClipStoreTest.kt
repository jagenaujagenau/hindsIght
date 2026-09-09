package earth.diego.hindsight.mobile.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `legacy and collision-safe clip names retain timestamp and duration`() {
        val old = temp.newFile("clip_20260909-120000_58s.m4a").apply { setLastModified(1_000) }
        val new = temp.newFile("clip_20260909-120000_58s_a901b256-4d00-4d00-8000-000000000000.m4a")
            .apply { setLastModified(2_000) }
        val legacy = ClipStore.toClip(old)
        val unique = ClipStore.toClip(new)
        assertEquals(58L, legacy.durationSeconds)
        assertEquals(58L, unique.durationSeconds)
        assertEquals(legacy.recordedAt, unique.recordedAt)
        assertTrue(unique.recordedAt > 1_000_000_000_000L)
        assertNotEquals(legacy.id, unique.id)
    }

    @Test fun `unrecognised names still use file metadata`() {
        val file = temp.newFile("imported.m4a").apply { setLastModified(2_000) }
        val clip = ClipStore.toClip(file)
        assertEquals(file.lastModified(), clip.recordedAt)
        assertEquals(0L, clip.durationSeconds)
    }
}
