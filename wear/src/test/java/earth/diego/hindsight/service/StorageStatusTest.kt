package earth.diego.hindsight.service

import org.junit.Assert.*
import org.junit.Test

class StorageStatusTest {
    @Test fun `save needs its estimated bytes plus the system reserve`() {
        StoragePolicy.requireSpace(StoragePolicy.RESERVE_BYTES + 100, 100)
        assertThrows(InsufficientStorage::class.java) { StoragePolicy.requireSpace(StoragePolicy.RESERVE_BYTES + 99, 100) }
        assertThrows(InsufficientStorage::class.java) { StoragePolicy.requireSpace(0, 0) }
    }
    @Test fun `low free space and a large outbox warn without deleting anything`() {
        assertNotNull(StorageStatus(49 * StoragePolicy.MIB, 0, 0).warning)
        assertNotNull(StorageStatus(1000 * StoragePolicy.MIB, 100 * StoragePolicy.MIB, 5).warning)
        assertNull(StorageStatus(1000 * StoragePolicy.MIB, 0, 0).warning)
    }
    @Test fun `low battery warning excludes charging and unknown readings`() {
        assertTrue(BatteryStatus(15, false).low)
        assertFalse(BatteryStatus(16, false).low)
        assertFalse(BatteryStatus(10, true).low)
        assertFalse(BatteryStatus(-1, false).low)
    }
}
