package earth.diego.hindsight.service

import java.io.IOException

/** Informational outbox usage, never a license to delete saved audio. */
data class StorageStatus(val freeBytes: Long, val pendingBytes: Long, val pendingCount: Int) {
    val warning: String?
        get() = when {
            freeBytes < StoragePolicy.WARNING_FREE_BYTES -> "Low storage. Sync saved clips to your phone."
            pendingBytes >= StoragePolicy.WARNING_OUTBOX_BYTES -> "Saved clips are using space. Connect your phone to sync."
            else -> null
        }
}

internal object StoragePolicy {
    const val MIB = 1024L * 1024L
    const val RESERVE_BYTES = 20 * MIB
    const val WARNING_FREE_BYTES = 50 * MIB
    const val WARNING_OUTBOX_BYTES = 100 * MIB

    fun requireSpace(freeBytes: Long, additionalBytes: Long) {
        if (freeBytes < RESERVE_BYTES || additionalBytes > freeBytes - RESERVE_BYTES) {
            throw InsufficientStorage()
        }
    }
}

internal class InsufficientStorage : IOException("Not enough free space. Sync clips or free storage, then retry. Saved clips were kept.")

data class BatteryStatus(val percent: Int, val charging: Boolean) {
    val low: Boolean get() = percent in 0..15 && !charging
}
