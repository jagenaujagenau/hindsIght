package earth.diego.hindsight.mobile.transcribe

/**
 * Joins the per-chunk transcripts back into one piece of text.
 *
 * Chunks deliberately overlap by a few seconds so speech on a boundary is heard
 * whole by at least one of them. The cost is that the shared audio is recognised
 * twice, and rarely identically — one real example produced
 * "...and mentioned the second half of the plan clearly" followed by
 * "Mentioning the second half of the plan properly...", which reads as a stutter.
 *
 * An exact-containment check cannot catch that, because neither rendering
 * contains the other. So instead we look for the longest run of words the two
 * agree on across the seam and splice there, keeping one rendering of the
 * overlap rather than both.
 */
object TranscriptStitcher {

    /** How far into each side to look for the seam; comfortably more than the overlap. */
    private const val LOOKBACK_WORDS = 30

    /** Shorter agreements than this happen by chance between unrelated sentences. */
    private const val MIN_RUN_WORDS = 3

    fun stitch(parts: List<String>): String =
        parts.filter { it.isNotBlank() }.fold("") { accumulated, next -> append(accumulated, next) }

    internal fun append(accumulated: String, next: String): String {
        if (accumulated.isBlank()) return next.trim()
        if (next.isBlank()) return accumulated

        val left = accumulated.trim().split(WHITESPACE)
        val right = next.trim().split(WHITESPACE)

        // A chunk that adds nothing new — silence either side of one utterance —
        // would otherwise repeat the previous chunk wholesale.
        if (normalise(right).isNotEmpty() && containsRun(normalise(left), normalise(right))) {
            return accumulated
        }

        val seam = findSeam(left, right) ?: return "$accumulated ${next.trim()}"
        return (left.take(seam.leftStart) + right.drop(seam.rightStart)).joinToString(" ")
    }

    private data class Seam(val leftStart: Int, val rightStart: Int, val length: Int)

    /**
     * Longest run of words shared between the tail of [left] and the head of
     * [right]. Only the seam region is considered, so a phrase repeated much
     * later in the recording cannot be mistaken for an overlap.
     */
    private fun findSeam(left: List<String>, right: List<String>): Seam? {
        val leftOffset = (left.size - LOOKBACK_WORDS).coerceAtLeast(0)
        val tail = normalise(left.drop(leftOffset))
        val head = normalise(right.take(LOOKBACK_WORDS))
        if (tail.isEmpty() || head.isEmpty()) return null

        var best: Seam? = null
        // Classic longest-common-substring table, over words rather than characters.
        val lengths = Array(tail.size + 1) { IntArray(head.size + 1) }
        for (i in 1..tail.size) {
            for (j in 1..head.size) {
                if (tail[i - 1] != head[j - 1]) continue
                val run = lengths[i - 1][j - 1] + 1
                lengths[i][j] = run
                if (run >= MIN_RUN_WORDS && run > (best?.length ?: 0)) {
                    best = Seam(
                        leftStart = leftOffset + i - run,
                        rightStart = j - run,
                        length = run,
                    )
                }
            }
        }
        return best
    }

    private fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            if ((needle.indices).all { haystack[start + it] == needle[it] }) return true
        }
        return false
    }

    /** Compare on words alone: case and punctuation vary between recognitions. */
    private fun normalise(words: List<String>): List<String> =
        words.map { word -> word.lowercase().filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
}
