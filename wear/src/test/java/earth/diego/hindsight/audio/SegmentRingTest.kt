package earth.diego.hindsight.audio

import earth.diego.hindsight.shared.AudioSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SegmentRingTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var ring: SegmentRing
    private var nextIndex = 0L

    @Before
    fun setUp() {
        ring = SegmentRing(temp.newFolder("ring"))
    }

    private fun addFullSegment(): File {
        val file = ring.nextSegmentFile(nextIndex++)
        file.writeBytes(ByteArray(16))
        ring.add(Segment(file, AudioSpec.FRAMES_PER_SEGMENT))
        return file
    }

    @Test
    fun `evicts oldest segments beyond the retention window`() {
        // Four full segments cover two minutes, including the trim boundary.
        ring.setRetentionFrames(AudioSpec.framesForMinutes(2))

        val files = (1..12).map { addFullSegment() }

        assertEquals(4, ring.segmentCount)
        assertTrue("newest segments survive", files.takeLast(4).all { it.exists() })
        assertFalse("oldest segments are deleted", files.first().exists())
    }

    @Test
    fun `frequent saves do not shorten the retained window`() {
        val retention = AudioSpec.framesForMinutes(1)
        ring.setRetentionFrames(retention)
        val shortFrames = 78 // Each save closes a segment after about five seconds.
        repeat(30) {
            val file = ring.nextSegmentFile(nextIndex++)
            file.writeBytes(ByteArray(16))
            ring.add(Segment(file, shortFrames))
        }

        assertTrue("a full minute must survive frequent saves", ring.bufferedFrames >= retention)
        assertTrue("keep only the boundary segment as slack", ring.bufferedFrames < retention + shortFrames)
        assertTrue(ring.pinNewest(retention).sumOf { it.frameCount } >= retention)
    }

    @Test
    fun `shrinking retention reclaims storage immediately`() {
        ring.setRetentionFrames(AudioSpec.framesForMinutes(10))
        repeat(25) { addFullSegment() }
        val beforeCount = ring.segmentCount

        ring.setRetentionFrames(AudioSpec.framesForMinutes(1))

        assertTrue(ring.segmentCount < beforeCount)
        assertEquals(2, ring.segmentCount)
    }

    @Test
    fun `pinned segments survive eviction until released`() {
        ring.setRetentionFrames(AudioSpec.framesForMinutes(1))
        repeat(3) { addFullSegment() }

        val pinned = ring.pinNewest(AudioSpec.framesForMinutes(1))
        val pinnedFiles = pinned.map { it.file }
        assertTrue(pinnedFiles.isNotEmpty())

        // Push the pinned segments far out of the window while a save is in flight.
        repeat(20) { addFullSegment() }
        assertTrue("pinned files must stay readable", pinnedFiles.all { it.exists() })

        ring.unpin(pinned)
        assertTrue("deletion is deferred, not skipped", pinnedFiles.none { it.exists() })
    }

    @Test
    fun `pinNewest returns oldest-first and covers the requested window`() {
        ring.setRetentionFrames(AudioSpec.framesForMinutes(5))
        val added = (1..10).map { addFullSegment() }

        val requested = AudioSpec.framesForMinutes(2)
        val pinned = ring.pinNewest(requested)

        assertTrue(pinned.sumOf { it.frameCount } >= requested)
        assertEquals(
            "segments must be ordered oldest first for concatenation",
            pinned.map { it.file.name }.sorted(),
            pinned.map { it.file.name },
        )
        assertEquals(added.last(), pinned.last().file)
    }
}
