package earth.diego.hindsight.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.lifecycle.lifecycleScope
import earth.diego.hindsight.mobile.audio.Waveform
import earth.diego.hindsight.mobile.audio.WaveformWorker
import earth.diego.hindsight.mobile.data.Clip
import earth.diego.hindsight.mobile.data.ClipStore
import earth.diego.hindsight.mobile.player.ClipPlayer
import earth.diego.hindsight.mobile.sync.SyncOutcome
import earth.diego.hindsight.mobile.sync.WatchSync
import earth.diego.hindsight.mobile.sync.WatchSyncStatus
import earth.diego.hindsight.mobile.ui.LibraryScreen
import earth.diego.hindsight.mobile.transcribe.TranscribeWorker
import earth.diego.hindsight.mobile.ui.PlayerScreen
import earth.diego.hindsight.mobile.ui.components.TranscriptState
import earth.diego.hindsight.mobile.ui.theme.HindsightTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val player = ClipPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            HindsightTheme {
                HindsightApp(player)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The receiver service may have landed clips while we were away.
        lifecycleScope.launch {
            ClipStore.refresh(this@MainActivity)
            WaveformWorker.enqueue(this@MainActivity)
        }
    }

    override fun onStop() {
        super.onStop()
        player.release()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HindsightApp(player: ClipPlayer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val clips by ClipStore.clips.collectAsStateWithLifecycle()
    val playback by player.state.collectAsStateWithLifecycle()

    var openClipId by remember { mutableStateOf<String?>(null) }
    val openClip = remember(clips, openClipId) { clips.firstOrNull { it.id == openClipId } }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifications = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* best-effort: only affects the "clip arrived" notification */ }
        LaunchedEffect(Unit) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    // Keep the position bar honest only while something is playing.
    LaunchedEffect(playback.clipId, playback.playing) {
        while (playback.clipId != null && playback.playing) {
            player.syncPosition()
            delay(100)
        }
    }

    // Pull-to-sync: ask the watch to flush its outbox, and report what it says.
    var syncing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        WatchSyncStatus.pending.collect { pending ->
            syncing = false
            snackbarHostState.showSnackbar(
                when (pending) {
                    0 -> "Watch has nothing waiting"
                    1 -> "Sending 1 clip from your watch"
                    else -> "Sending $pending clips from your watch"
                },
            )
        }
    }

    BackHandler(enabled = openClipId != null) { openClipId = null }

    // One orchestrated moment: the row's waveform is the same object as the
    // player's, so tapping a clip grows it rather than replacing the screen.
    SharedTransitionLayout {
        AnimatedContent(
            targetState = openClip,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "screen",
        ) { clip ->
        if (clip == null) {
            LibraryScreen(
                clips = clips,
                animatedVisibilityScope = this@AnimatedContent,
                playingClipId = playback.clipId.takeIf { playback.playing },
                snackbarHostState = snackbarHostState,
                isSyncing = syncing,
                onSync = {
                    syncing = true
                    scope.launch {
                        ClipStore.refresh(context)
                        when (val outcome = WatchSync.requestSync(context)) {
                            is SyncOutcome.Asked -> {
                                // The watch answers on a Play services callback; if it
                                // never does, do not spin forever.
                                delay(6_000)
                                if (syncing) {
                                    syncing = false
                                    snackbarHostState.showSnackbar("Watch did not answer")
                                }
                            }
                            SyncOutcome.NoWatch -> {
                                syncing = false
                                snackbarHostState.showSnackbar("No watch connected")
                            }
                            is SyncOutcome.Failed -> {
                                syncing = false
                                snackbarHostState.showSnackbar(outcome.reason)
                            }
                        }
                    }
                },
                onOpen = { selected ->
                    openClipId = selected.id
                    player.open(selected.id, selected.file)
                },
            )
        } else {
            // Observe this clip's transcription job so progress survives leaving
            // and returning to the screen.
            val workInfo by remember(clip.id) { TranscribeWorker.observe(context, clip.id) }
                .collectAsStateWithLifecycle(initialValue = null)

            val transcriptState = when {
                clip.transcript != null -> TranscriptState.Ready(clip.transcript!!)
                workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED ->
                    TranscriptState.Running(
                        done = workInfo?.progress?.getInt(TranscribeWorker.KEY_DONE, 0) ?: 0,
                        total = workInfo?.progress?.getInt(TranscribeWorker.KEY_TOTAL, 0) ?: 0,
                    )
                workInfo?.state == WorkInfo.State.FAILED -> TranscriptState.Failed(
                    workInfo?.outputData?.getString(TranscribeWorker.KEY_ERROR)
                        ?: "Transcription failed",
                )
                else -> TranscriptState.None
            }

            PlayerScreen(
                clip = clip,
                animatedVisibilityScope = this@AnimatedContent,
                transcript = transcriptState,
                onTranscribe = { TranscribeWorker.start(context, clip.id) },
                onCancelTranscribe = { TranscribeWorker.cancel(context, clip.id) },
                playback = playback,
                onBack = { openClipId = null },
                onTogglePlay = {
                    if (playback.clipId != clip.id) player.open(clip.id, clip.file)
                    else player.togglePlayPause()
                },
                onSeekFraction = player::seekToFraction,
                onSkip = player::skip,
                onSpeed = player::setSpeed,
                onRename = { ClipStore.rename(context, clip, it) },
                onShare = { shareClip(context, clip) },
                onDelete = {
                    val moved = ClipStore.moveToTrash(context, clip)
                    if (playback.clipId == clip.id) player.release()
                    openClipId = null
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Clip deleted",
                            actionLabel = "Undo",
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            ClipStore.restore(context, moved)
                        } else {
                            ClipStore.purge(moved)
                        }
                    }
                },
            )
        }
        }
    }
}

private fun shareClip(context: android.content.Context, clip: Clip) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.clips", clip.file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, clip.displayTitle)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share clip"))
}
