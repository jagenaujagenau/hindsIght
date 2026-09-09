package earth.diego.hindsight.audio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AtomicClipTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `outbox sees a clip only after it is complete`() {
        val output = File(temp.root, "clip.m4a")
        AtomicClip.write(output) { partial ->
            partial.writeText("audio")
            assertFalse(output.exists())
            assertTrue(temp.root.listFiles().orEmpty().none { it.extension == "m4a" })
        }
        assertEquals("audio", output.readText())
        assertFalse(File(temp.root, "clip.m4a.part").exists())
    }

    @Test fun `a failed build never leaves an uploadable file`() {
        val output = File(temp.root, "clip.m4a")
        assertThrows(IOException::class.java) {
            AtomicClip.write(output) { partial ->
                partial.writeText("incomplete")
                throw IOException("disk full")
            }
        }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `an empty build is not a success`() {
        val output = File(temp.root, "clip.m4a")
        assertThrows(IllegalStateException::class.java) { AtomicClip.write(output) { it.createNewFile() } }
        assertFalse(output.exists())
    }

    @Test fun `session cleanup removes crash leftovers but keeps saved audio`() {
        val saved = temp.newFile("saved.m4a")
        val incomplete = temp.newFile("aborted.m4a.part")
        AtomicClip.discardIncomplete(temp.root)
        assertTrue(saved.exists())
        assertFalse(incomplete.exists())
    }

    @Test fun `existing saved audio is never replaced`() {
        val output = temp.newFile("clip.m4a").apply { writeText("original") }
        assertThrows(IllegalStateException::class.java) { AtomicClip.write(output) { it.writeText("replacement") } }
        assertEquals("original", output.readText())
    }
}
