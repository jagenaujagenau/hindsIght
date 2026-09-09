package earth.diego.hindsight.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One transfer at a time across immediate wakeups and delayed retry workers. */
internal class UploadDrain {
    private val mutex = Mutex()

    suspend fun drain(pending: () -> List<File>, send: suspend (File) -> Unit): Boolean = mutex.withLock {
        val attempted = mutableSetOf<String>()
        while (true) {
            // Re-scan after every batch: clips saved during a transfer belong to
            // this drain too. Unacknowledged files get at most one attempt per run.
            val batch = pending().filter { it.name !in attempted }
            if (batch.isEmpty()) break
            for (file in batch) {
                attempted += file.name
                if (file.exists()) send(file)
            }
        }
        pending().isNotEmpty()
    }
}
