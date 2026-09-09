package earth.diego.hindsight.audio

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real start/stop/join code without needing a hardware AAC encoder. */
class RingRecorderLifecycleTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `stop suspends its caller until the thread actually exits`() = runBlocking {
        val release = CountDownLatch(1)
        val recorder = RingRecorder(temp.newFolder()) {
            Thread { release.await(3, TimeUnit.SECONDS) }
        }
        recorder.start(1)
        val stopped = async(start = CoroutineStart.UNDISPATCHED) { recorder.stop() }
        try {
            // A blocking join on this coroutine's thread would have waited for
            // the safety timeout, completing stop before reaching this assertion.
            assertFalse("shutdown must yield to the UI thread", stopped.isCompleted)
        } finally {
            release.countDown()
            stopped.await()
        }
        assertEquals(CaptureState(), recorder.state.value)
    }

    @Test fun `restart cannot overlap a thread still shutting down`() = runBlocking {
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val starts = AtomicInteger()
        var previous: Thread? = null
        val recorder = RingRecorder(temp.newFolder()) {
            assertFalse("old capture must really have exited", previous?.isAlive == true)
            val latch = if (starts.incrementAndGet() == 1) releaseFirst else releaseSecond
            Thread { latch.await(3, TimeUnit.SECONDS) }.also { previous = it }
        }
        recorder.start(1)
        val stopped = async(start = CoroutineStart.UNDISPATCHED) { recorder.stop() }
        val restarted = async(start = CoroutineStart.UNDISPATCHED) { recorder.start(5) }
        try {
            assertEquals(1, starts.get())
            assertFalse(restarted.isCompleted)
            releaseFirst.countDown()
            stopped.await()
            restarted.await()
            assertEquals(2, starts.get())
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            stopped.await()
            restarted.await()
            recorder.stop()
        }
    }
}
