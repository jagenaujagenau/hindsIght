package earth.diego.hindsight.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SaveBeforeStopTest {
    @Test fun `failed save leaves recorder and listening intent alone`() = runBlocking {
        var stopped = false
        assertFalse(saveBeforeStop(save = { false }, stop = { stopped = true }))
        assertFalse(stopped)
    }
    @Test fun `stop waits for durable save completion`() = runBlocking {
        val saved = CompletableDeferred<Boolean>()
        var stopped = false
        val action = async(start = CoroutineStart.UNDISPATCHED) {
            saveBeforeStop(save = { saved.await() }, stop = { stopped = true })
        }
        assertFalse(stopped)
        saved.complete(true)
        assertTrue(action.await())
        assertTrue(stopped)
    }
    @Test fun `cancellation before success cannot discard audio`() = runBlocking {
        var stopped = false
        val action = async(start = CoroutineStart.UNDISPATCHED) {
            saveBeforeStop(save = { CompletableDeferred<Boolean>().await() }, stop = { stopped = true })
        }
        action.cancelAndJoin()
        assertFalse(stopped)
    }
}
