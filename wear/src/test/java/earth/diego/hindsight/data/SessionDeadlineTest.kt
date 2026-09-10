package earth.diego.hindsight.data

import org.junit.Assert.*
import org.junit.Test

class SessionDeadlineTest {
    @Test fun `off has no deadline`() {
        assertNull(SessionDeadline.start(SessionLimit.OFF, 1000, 2000, 1))
    }
    @Test fun `timer uses monotonic time even when wall clock changes`() {
        val deadline = SessionDeadline.start(SessionLimit.FIFTEEN, 10_000, 20_000, 1)!!
        assertEquals(899_000L, deadline.remainingMs(9_000_000, 21_000, 1))
        assertEquals(899_000L, deadline.remainingMs(0, 21_000, 1))
    }
    @Test fun `restoring a deadline does not restart the session`() {
        val deadline = SessionDeadline.start(SessionLimit.THIRTY, 10_000, 20_000, 1)!!
        val restored = deadline.copy()
        assertEquals(600_000L, restored.remainingMs(1_210_000, 1_220_000, 1))
    }
    @Test fun `reboot falls back to wall time and expiry stays zero`() {
        val deadline = SessionDeadline.start(SessionLimit.FIFTEEN, 10_000, 20_000, 1)!!
        assertEquals(300_000L, deadline.remainingMs(610_000, 1000, 2))
        assertEquals(0L, deadline.remainingMs(910_000, 1000, 2))
        assertEquals(0L, deadline.remainingMs(0, 999_999, 1))
    }
    @Test fun `clock moving backwards across reboot cannot exceed selected duration`() {
        val deadline = SessionDeadline.start(SessionLimit.FIFTEEN, 10_000, 20_000, 1)!!
        assertEquals(900_000L, deadline.remainingMs(0, 0, 2))
    }
}
