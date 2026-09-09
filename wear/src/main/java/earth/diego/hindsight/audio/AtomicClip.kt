package earth.diego.hindsight.audio

import java.io.File
import java.io.RandomAccessFile

/** Only complete, fsynced clips are exposed to the outbox's .m4a scan. */
internal object AtomicClip {
    /** Call only at session initialization, after any previous writer has stopped. */
    fun discardIncomplete(directory: File) {
        directory.listFiles { file -> file.isFile && file.name.endsWith(".m4a.part") }
            ?.forEach { it.delete() }
    }

    fun write(output: File, build: (File) -> Unit): File {
        check(!output.exists()) { "Refusing to replace an existing clip" }
        val partial = File(output.parentFile, "${output.name}.part")
        try {
            build(partial)
            check(partial.length() > 0) { "Empty clip" }
            RandomAccessFile(partial, "rw").use { it.fd.sync() }
            check(partial.renameTo(output)) { "Could not finalise clip" }
            return output
        } finally {
            partial.delete()
        }
    }
}
