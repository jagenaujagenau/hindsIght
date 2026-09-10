package earth.diego.hindsight.ui

import org.junit.Assert.*
import org.junit.Test

class PermissionRecoveryTest {
    @Test fun `first request is not mistaken for permanent denial`() {
        assertFalse(needsPermissionSettings(requested = false, granted = false, showRationale = false))
    }
    @Test fun `denial without rationale directs to settings`() {
        assertTrue(needsPermissionSettings(requested = true, granted = false, showRationale = false))
        assertFalse(needsPermissionSettings(requested = true, granted = false, showRationale = true))
    }
    @Test fun `granting access in settings clears denied state`() {
        assertFalse(needsPermissionSettings(requested = true, granted = true, showRationale = false))
    }
}
