package earth.diego.hindsight.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadDrainTest {
    @get:Rule val temp = TemporaryFolder()
    private fun pending(): List<File> = temp.root.listFiles().orEmpty().toList()

    @Test fun `a clip saved during a transfer is drained without another worker`() = runBlocking {
        temp.newFile("first.m4a")
        val sent = mutableListOf<String>()
        val needsRetry = UploadDrain().drain(::pending) { file ->
            sent += file.name
            file.delete() // durable phone ack
            if (sent.size == 1) temp.newFile("second.m4a")
        }
        assertEquals(listOf("first.m4a", "second.m4a"), sent)
        assertFalse(needsRetry)
    }

    @Test fun `unacknowledged files are retained and attempted only once per drain`() = runBlocking {
        val file = temp.newFile("clip.m4a")
        var attempts = 0
        val needsRetry = UploadDrain().drain(::pending) { attempts++ }
        assertTrue(needsRetry)
        assertTrue(file.exists())
        assertEquals(1, attempts)
    }

    @Test fun `immediate and retry workers cannot transfer concurrently`() = runBlocking {
        withTimeout(3_000) {
            temp.newFile("clip.m4a")
            val drain = UploadDrain()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var secondSent = false
            val first = async {
                drain.drain(::pending) {
                    entered.complete(Unit)
                    release.await()
                    it.delete()
                }
            }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                drain.drain(::pending) { secondSent = true }
            }
            assertFalse(secondSent)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertFalse(first.await())
            assertFalse(second.await())
            assertFalse(secondSent)
        }
    }

    @Test fun `offline preparation retains files and completes as retryable`() = runBlocking {
        temp.newFile("clip.m4a")
        var completion: Boolean? = null
        var sent = false
        assertTrue(UploadDrain().drain(::pending, prepare = { false }, completed = { completion = it }) { sent = true })
        assertEquals(true, completion)
        assertFalse(sent)
        assertEquals(1, pending().size)
    }

    @Test fun `discovery and completion are ordered inside the transfer lock`() = runBlocking {
        val events = mutableListOf<String>()
        temp.newFile("clip.m4a")
        val drain = UploadDrain()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            drain.drain(::pending, prepare = { events += "first-discovery"; true }, completed = { events += "first-complete" }) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            drain.drain(::pending, prepare = { events += "second-discovery"; true }, completed = { events += "second-complete" }) { it.delete() }
        }
        assertEquals(listOf("first-discovery"), events)
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf("first-discovery", "first-complete", "second-discovery", "second-complete"), events)
    }

    @Test fun `cancellation keeps the file and releases the transfer lock`() = runBlocking {
        withTimeout(3_000) {
            val file = temp.newFile("clip.m4a")
            val drain = UploadDrain()
            val entered = CompletableDeferred<Unit>()
            val worker = async {
                drain.drain(::pending) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
            entered.await()
            worker.cancelAndJoin()
            assertTrue(file.exists())
            assertFalse(drain.drain(::pending) { it.delete() })
        }
    }
}
