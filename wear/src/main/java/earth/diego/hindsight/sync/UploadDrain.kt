package earth.diego.hindsight.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Discovery, transfer and final status share one lock across immediate and retry work. */
internal class UploadDrain {
    private val mutex = Mutex()

    suspend fun drain(
        pending: () -> List<File>,
        prepare: suspend () -> Boolean = { true },
        completed: (needsRetry: Boolean) -> Unit = {},
        send: suspend (File) -> Unit,
    ): Boolean = mutex.withLock {
        var needsRetry = true
        try {
            if (pending().isEmpty()) {
                needsRetry = false
                return@withLock false
            }
            if (!prepare()) return@withLock true
            val attempted = mutableSetOf<String>()
            while (true) {
                val batch = pending().filter { it.name !in attempted }
                if (batch.isEmpty()) break
                for (file in batch) {
                    attempted += file.name
                    if (file.exists()) send(file)
                }
            }
            needsRetry = pending().isNotEmpty()
            needsRetry
        } finally {
            // Complete before unlocking: a finishing worker must not overwrite
            // the Sending status of the next worker acquiring this drain.
            completed(needsRetry)
        }
    }
}
