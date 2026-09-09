package earth.diego.hindsight.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.foundation.pager.HorizontalPager
import earth.diego.hindsight.data.RecorderSettings
import earth.diego.hindsight.data.Appearance
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.RecorderService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val PAGE_WAVE = 0
private const val PAGE_SETTINGS = 1

@Composable
fun RecorderApp(initiallyGranted: Boolean, requiredPermissions: Array<String>, startRequested: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(initiallyGranted) }
    var pendingStartRequest by rememberSaveable { mutableStateOf(startRequested) }
    val settings = remember { RecorderSettings(context) }
    val appearance by settings.appearance.collectAsStateWithLifecycle(Appearance())
    // The app shell needs transitions, not the once-per-second buffer counter.
    val recordingFlow = remember { RecorderBus.capture.map { it.recording }.distinctUntilChanged() }
    val recording by recordingFlow.collectAsStateWithLifecycle(false)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        granted = results[android.Manifest.permission.RECORD_AUDIO] ?: granted
    }

    LaunchedEffect(granted) {
        if (!granted) permissionLauncher.launch(requiredPermissions)
    }

    // The service reads persisted intent before starting. No optimistic `true`
    // default, and no effect keyed to capture failure that could restart in a loop.
    LaunchedEffect(granted) {
        if (granted) {
            if (pendingStartRequest) {
                pendingStartRequest = false
                RecorderService.start(context)
            } else if (RecorderBus.capture.value.error == null) RecorderService.restore(context)
        }
    }

    HindsightTheme {
        AppScaffold {
            if (!granted) {
                PermissionScreen { permissionLauncher.launch(requiredPermissions) }
                return@AppScaffold
            }

            // Swipe left for recording controls, then Appearance for visual choices.
            val pagerState = rememberPagerState { 2 }

            HorizontalPagerScaffold(pagerState = pagerState) {
                HorizontalPager(state = pagerState) { page ->
                    when (page) {
                        PAGE_WAVE -> {
                            val capture by RecorderBus.capture.collectAsStateWithLifecycle()
                            val save by RecorderBus.save.collectAsStateWithLifecycle()
                            val pending by RecorderBus.pendingUploads.collectAsStateWithLifecycle()
                            WaveScreen(
                                state = capture,
                                save = save,
                                pendingUploads = pending,
                                visible = pagerState.currentPage == PAGE_WAVE,
                                style = appearance.waveStyle,
                                accent = appearance.accent.color ?: MaterialTheme.colorScheme.primary,
                                sensitivity = appearance.sensitivity,
                                onSave = { RecorderService.save(context) },
                                onStop = { RecorderService.stop(context) },
                                onResume = { RecorderService.start(context) },
                            )
                        }

                        PAGE_SETTINGS -> SettingsScreen(
                            recording = recording,
                            onToggleRecording = {
                                if (recording) RecorderService.stop(context) else RecorderService.start(context)
                            },
                            retention = appearance.retention,
                            waveStyle = appearance.waveStyle,
                            accent = appearance.accent,
                            resolvedAccent = MaterialTheme.colorScheme.primary,
                            onWaveStyle = { scope.launch { settings.setWaveStyle(it) } },
                            onAccent = { scope.launch { settings.setAccent(it) } },
                            sensitivity = appearance.sensitivity,
                            onSensitivity = { scope.launch { settings.setSensitivity(it) } },
                            onRetention = { RecorderService.setRetention(context, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Button(onClick = onRequest) {
                Text(
                    "Allow microphone",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
