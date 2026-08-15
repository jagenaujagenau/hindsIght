package earth.diego.hindsight.mobile.transcribe

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.util.Log
import earth.diego.hindsight.mobile.audio.PcmDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

sealed interface TranscriptionResult {
    data class Success(val text: String, val segments: Int, val elapsedMs: Long) : TranscriptionResult
    data class Unavailable(val reason: String) : TranscriptionResult
    data class Failed(val reason: String) : TranscriptionResult
}

/**
 * On-device speech-to-text over a saved clip.
 *
 * Uses [SpeechRecognizer.createOnDeviceSpeechRecognizer], which on a Pixel is
 * backed by Android System Intelligence — so nothing leaves the phone and there
 * is no API key or network involved.
 *
 * The recogniser is built for live dictation, so two things matter here. It only
 * accepts raw PCM, which is why the clip is decoded first; and it is driven as a
 * *segmented session* keyed on the audio source, which is what lets it run to the
 * end of a file rather than stopping at the first pause it hears.
 */
object Transcriber {

    private const val TAG = "Transcriber"

    /** Generous: this runs faster than realtime, but a long clip is still minutes. */
    private const val TIMEOUT_MS = 15 * 60 * 1000L

    fun availability(context: Context): String? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            "Needs Android 13 or newer"

        !SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
            "On-device speech recognition is not available on this device"

        else -> null
    }

    suspend fun transcribe(
        context: Context,
        clip: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TranscriptionResult {
        availability(context)?.let { return TranscriptionResult.Unavailable(it) }

        val started = System.currentTimeMillis()

        val support = support(context)
        val chosen = chooseLanguage(support)
            ?: return TranscriptionResult.Unavailable(
                support.error ?: "No speech model is installed. Supported here: " +
                    support.supported.take(4).joinToString().ifEmpty { "none reported" },
            )
        Log.i(TAG, "Using $chosen (installed=${support.installed}, device locale=$language)")

        val pcm = File(context.cacheDir, "transcribe-${clip.name}.pcm")

        val info = withContext(Dispatchers.IO) {
            runCatching { PcmDecoder.decode(clip, pcm) }.getOrNull()
        } ?: return TranscriptionResult.Failed("Could not decode ${clip.name}")

        if (info.bytes <= 0) {
            pcm.delete()
            return TranscriptionResult.Failed("Clip decoded to no audio")
        }
        Log.i(TAG, "Decoded ${clip.name}: ${info.bytes} bytes, ${info.sampleRate} Hz, ${info.channels}ch")

        return try {
            // One bounded chunk at a time: the recogniser stops at the first real
            // silence, so handing it the whole file transcribes only the opening.
            val chunks = withContext(Dispatchers.IO) {
                PcmChunker.chunk(pcm, info.sampleRate, info.channels)
            }
            Log.i(TAG, "Transcribing ${chunks.size} chunk(s)")

            val slice = File(context.cacheDir, "transcribe-slice.pcm")
            val pieces = mutableListOf<String>()

            withTimeout(TIMEOUT_MS) {
                chunks.forEachIndexed { index, chunk ->
                    withContext(Dispatchers.IO) { writeSlice(pcm, chunk, slice) }
                    val text = withContext(Dispatchers.Main) {
                        runCatching {
                            recognise(context, slice, info.sampleRate, info.channels, chosen).first
                        }.getOrElse {
                            // A chunk of pure silence legitimately recognises nothing;
                            // that must not abort the rest of the recording.
                            Log.i(TAG, "Chunk ${index + 1}/${chunks.size}: ${it.message}")
                            ""
                        }
                    }
                    if (text.isNotBlank() && !isEchoOfPrevious(pieces.lastOrNull(), text)) {
                        pieces += text
                        Log.i(TAG, "Chunk ${index + 1}/${chunks.size}: ${text.take(60)}")
                    }
                    onProgress(index + 1, chunks.size)
                }
            }
            slice.delete()

            TranscriptionResult.Success(
                text = pieces.joinToString(" ").trim(),
                segments = chunks.size,
                elapsedMs = System.currentTimeMillis() - started,
            )
        } catch (t: TimeoutCancellationException) {
            TranscriptionResult.Failed("Timed out after ${TIMEOUT_MS / 1000}s")
        } catch (t: Throwable) {
            TranscriptionResult.Failed(t.message ?: "Recognition failed")
        } finally {
            pcm.delete()
        }
    }

    /** BCP-47 tag of the device locale — what we would ask for given a free choice. */
    val language: String get() = Locale.getDefault().toLanguageTag()

    data class Support(
        val installed: List<String>,
        val pending: List<String>,
        val supported: List<String>,
        val error: String? = null,
    )

    /**
     * Picks a language the device can actually transcribe with.
     *
     * The device locale is not a safe request: measured on a Pixel 10 set to
     * en-DE, only en-US was installed and asking for en-DE failed outright with
     * ERROR_LANGUAGE_NOT_SUPPORTED. So prefer an exact match, then any installed
     * variant of the same language, then whatever is installed.
     */
    fun chooseLanguage(support: Support): String? {
        val installed = support.installed
        if (installed.isEmpty()) return null
        val wanted = Locale.getDefault().toLanguageTag()
        installed.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }

        val wantedLanguage = Locale.getDefault().language
        installed.firstOrNull { Locale.forLanguageTag(it).language == wantedLanguage }?.let { return it }

        return installed.first()
    }

    /**
     * What the on-device recogniser can actually do here. The model is a download,
     * not a guarantee, so this distinguishes "unsupported" from "not fetched yet".
     */
    suspend fun support(context: Context): Support = withContext(Dispatchers.Main) {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val done = CompletableDeferred<Support>()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        recognizer.checkRecognitionSupport(
            intent,
            Executors.newSingleThreadExecutor(),
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    done.complete(
                        Support(
                            installed = support.installedOnDeviceLanguages,
                            pending = support.pendingOnDeviceLanguages,
                            supported = support.supportedOnDeviceLanguages,
                        ),
                    )
                }

                override fun onError(error: Int) {
                    done.complete(Support(emptyList(), emptyList(), emptyList(), describe(error)))
                }
            },
        )
        try {
            withTimeout(30_000) { done.await() }
        } catch (t: Throwable) {
            Support(emptyList(), emptyList(), emptyList(), "support check timed out")
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    /** Asks the platform to fetch the on-device model for [language]. */
    suspend fun downloadModel(context: Context): String = withContext(Dispatchers.Main) {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val done = CompletableDeferred<String>()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            recognizer.triggerModelDownload(
                intent,
                Executors.newSingleThreadExecutor(),
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = Unit
                    override fun onSuccess() { done.complete("downloaded") }
                    override fun onScheduled() { done.complete("scheduled in background") }
                    override fun onError(error: Int) { done.complete("download failed: ${describe(error)}") }
                },
            )
        } else {
            recognizer.triggerModelDownload(intent)
            done.complete("requested (no progress API below API 34)")
        }
        try {
            withTimeout(5 * 60_000) { done.await() }
        } catch (t: Throwable) {
            "download timed out"
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    private suspend fun recognise(
        context: Context,
        pcm: File,
        sampleRate: Int,
        channels: Int,
        chosenLanguage: String,
    ): Pair<String, Int> {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val descriptor = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
        val done = CompletableDeferred<Pair<String, Int>>()
        val pieces = mutableListOf<String>()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Without an explicit language the on-device recogniser fails outright
            // with ERROR_LANGUAGE_NOT_SUPPORTED rather than assuming a default.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, chosenLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, chosenLanguage)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels)
            // Ends the session when the file is exhausted, instead of at the first
            // pause — without this a long recording stops after one utterance.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle) {
                takeBest(results)?.let(pieces::add)
            }

            override fun onSegmentResults(segment: Bundle) {
                takeBest(segment)?.let {
                    pieces += it
                    Log.i(TAG, "Segment ${pieces.size}: ${it.take(60)}")
                }
            }

            override fun onEndOfSegmentedSession() {
                done.complete(pieces.joinToString(" ").trim() to pieces.size)
            }

            override fun onError(error: Int) {
                // A "no match" at the tail is normal once the audio runs out; only
                // treat it as fatal if nothing at all was recognised.
                if (pieces.isNotEmpty() &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    done.complete(pieces.joinToString(" ").trim() to pieces.size)
                } else {
                    done.completeExceptionally(IllegalStateException(describe(error)))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partial: Bundle) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)

        return try {
            done.await()
        } finally {
            runCatching { recognizer.destroy() }
            runCatching { descriptor.close() }
        }
    }

    /** Overlapping chunks can both hear the same phrase; keep it once. */
    private fun isEchoOfPrevious(previous: String?, current: String): Boolean {
        if (previous == null) return false
        val normalise = { text: String -> text.lowercase().filter { it.isLetterOrDigit() || it == ' ' } }
        return normalise(previous).contains(normalise(current))
    }

    private fun writeSlice(source: File, chunk: Chunk, destination: File) {
        java.io.RandomAccessFile(source, "r").use { input ->
            input.seek(chunk.startByte)
            destination.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var remaining = chunk.bytes
                while (remaining > 0) {
                    val want = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun takeBest(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognised"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language pack not downloaded"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Cannot check language support"
        else -> "Recognition error $error"
    }
}
