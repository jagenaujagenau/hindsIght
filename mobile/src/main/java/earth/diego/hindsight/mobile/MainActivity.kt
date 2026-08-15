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
import androidx.lifecycle.lifecycleScope
import earth.diego.hindsight.mobile.audio.Waveform
import earth.diego.hindsight.mobile.audio.WaveformWorker
import earth.diego.hindsight.mobile.data.Clip
import earth.diego.hindsight.mobile.data.ClipStore
import earth.diego.hindsight.mobile.player.ClipPlayer
import earth.diego.hindsight.mobile.ui.LibraryScreen
import earth.diego.hindsight.mobile.ui.PlayerScreen
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

@Composable
private fun HindsightApp(player: ClipPlayer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val clips by ClipStore.clips.collectAsStateWithLifecycle()
    val playback by player.state.collectAsStateWithLifecycle()

    var openClipId by remember { mutableStateOf<String?>(null) }
    val openClip = remember(clips, openClipId) { clips.firstOrNull { it.id == openClipId } }

    // Peaks are read off the main thread; a missing sidecar simply draws flat
    // until the worker catches up.
    var peaks by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(openClip?.id, openClip?.hasWaveform) {
        peaks = null
        val clip = openClip ?: return@LaunchedEffect
        peaks = withContext(Dispatchers.IO) {
            Waveform.read(clip.file)?.let(Waveform::normalised)
        }
        // Sidecar not ready yet — poll gently rather than making the user leave
        // and come back.
        if (peaks == null) {
            repeat(10) {
                delay(1_500)
                val ready = withContext(Dispatchers.IO) {
                    Waveform.read(clip.file)?.let(Waveform::normalised)
                }
                if (ready != null) {
                    peaks = ready
                    return@LaunchedEffect
                }
            }
        }
    }

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

    BackHandler(enabled = openClipId != null) { openClipId = null }

    AnimatedContent(
        targetState = openClip,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
            } else {
                fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            }
        },
        label = "screen",
    ) { clip ->
        if (clip == null) {
            LibraryScreen(
                clips = clips,
                playingClipId = playback.clipId.takeIf { playback.playing },
                snackbarHostState = snackbarHostState,
                onOpen = { selected ->
                    openClipId = selected.id
                    player.open(selected.id, selected.file)
                },
            )
        } else {
            PlayerScreen(
                clip = clip,
                peaks = peaks,
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
