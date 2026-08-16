package earth.diego.hindsight.mobile.transcribe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import earth.diego.hindsight.mobile.data.ClipStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only harness for measuring on-device transcription against a real clip:
 *
 *   adb shell am broadcast -a earth.diego.hindsight.TRANSCRIBE \
 *       -p earth.diego.hindsight --es clip <filename.m4a>
 *
 * Exists because the recogniser is built for live dictation, and whether it
 * copes with long recorded audio is a question to answer with measurements
 * rather than a UI built on an assumption. Not merged into release builds.
 */
class TranscribeProbeReceiver : BroadcastReceiver() {

    private companion object { const val TAG = "TranscribeProbe" }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        ClipStore.refreshNow(app)

        // --ez sync true: exercises the pull-to-sync round trip without a finger.
        if (intent.getBooleanExtra("sync", false)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                Log.i(TAG, "PROBE sync request -> ${earth.diego.hindsight.mobile.sync.WatchSync.requestSync(app)}")
            }
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                earth.diego.hindsight.mobile.sync.WatchSyncStatus.pending.collect {
                    Log.i(TAG, "PROBE watch reports pending=$it")
                }
            }
            return
        }

        val requested = intent.getStringExtra("clip")
        val clip = ClipStore.clips.value.let { clips ->
            requested?.let { name -> clips.firstOrNull { it.id == name } } ?: clips.firstOrNull()
        }
        if (clip == null) {
            Log.w(TAG, "PROBE no clips available")
            return
        }

        // --es via worker: exercises the same path the UI button uses, including
        // the sidecar write and library refresh.
        if (intent.getBooleanExtra("worker", false)) {
            Log.i(TAG, "PROBE enqueueing worker for ${clip.id}")
            TranscribeWorker.start(app, clip.id)
            return
        }

        Log.i(TAG, "PROBE start ${clip.id} (${clip.durationSeconds}s)")
        Log.i(TAG, "PROBE availability=${Transcriber.availability(app) ?: "ok"}")

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            Log.i(TAG, "PROBE language=${Transcriber.language}")
            val support = Transcriber.support(app)
            Log.i(TAG, "PROBE support installed=${support.installed} pending=${support.pending} error=${support.error}")
            Log.i(TAG, "PROBE chosen=${Transcriber.chooseLanguage(support)}")
            if (intent.getBooleanExtra("download", false)) {
                Log.i(TAG, "PROBE download=${Transcriber.downloadModel(app)}")
            }
            when (val result = Transcriber.transcribe(app, clip.file)) {
                is TranscriptionResult.Success -> {
                    Log.i(TAG, "PROBE ok segments=${result.segments} elapsed=${result.elapsedMs}ms")
                    Log.i(TAG, "PROBE chars=${result.text.length} words=${result.text.split(' ').size}")
                    Log.i(TAG, "PROBE text=${result.text.take(400)}")
                }
                is TranscriptionResult.Unavailable -> Log.w(TAG, "PROBE unavailable: ${result.reason}")
                is TranscriptionResult.Failed -> Log.w(TAG, "PROBE failed: ${result.reason}")
            }
        }
    }
}
