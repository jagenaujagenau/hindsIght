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
import kotlinx.coroutines.launch

private const val PAGE_WAVE = 0
private const val PAGE_SETTINGS = 1

@Composable
fun RecorderApp(initiallyGranted: Boolean, requiredPermissions: Array<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(initiallyGranted) }
    val settings = remember { RecorderSettings(context) }
    val appearance by settings.appearance.collectAsStateWithLifecycle(Appearance())
    val capture by RecorderBus.capture.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        granted = results[android.Manifest.permission.RECORD_AUDIO] ?: granted
    }

    LaunchedEffect(granted) {
        if (!granted) permissionLauncher.launch(requiredPermissions)
    }

    // Opening the app *is* the start gesture — there is no idle screen to press
    // through. Driven off persisted intent rather than a composition flag, so a
    // service that died is picked back up instead of leaving a dead flat line.
    val listening by settings.listening.collectAsStateWithLifecycle(true)
    LaunchedEffect(granted, listening, capture.recording) {
        if (granted && listening && !capture.recording) RecorderService.start(context)
    }

    HindsightTheme {
        AppScaffold {
            if (!granted) {
                PermissionScreen { permissionLauncher.launch(requiredPermissions) }
                return@AppScaffold
            }

            // Settings is a page, not a destination: swipe left to reach it,
            // swipe back to return. No buttons anywhere.
            val pagerState = rememberPagerState { 2 }

            HorizontalPagerScaffold(pagerState = pagerState) {
                HorizontalPager(state = pagerState) { page ->
                    when (page) {
                        PAGE_WAVE -> WaveScreen(
                            state = capture,
                            style = appearance.waveStyle,
                            accent = appearance.accent.color ?: MaterialTheme.colorScheme.primary,
                            sensitivity = appearance.sensitivity,
                            onSave = { RecorderService.save(context) },
                            onStop = {
                                scope.launch { settings.setListening(false) }
                                RecorderService.stop(context)
                            },
                            onResume = { scope.launch { settings.setListening(true) } },
                        )

                        PAGE_SETTINGS -> SettingsScreen(
                            retention = appearance.retention,
                            waveStyle = appearance.waveStyle,
                            accent = appearance.accent,
                            resolvedAccent = MaterialTheme.colorScheme.primary,
                            onWaveStyle = { scope.launch { settings.setWaveStyle(it) } },
                            onAccent = { scope.launch { settings.setAccent(it) } },
                            sensitivity = appearance.sensitivity,
                            onSensitivity = { scope.launch { settings.setSensitivity(it) } },
                            onRetention = { choice ->
                                if (capture.recording) {
                                    // Live change: the service resizes the ring and persists it.
                                    RecorderService.setRetention(context, choice)
                                } else {
                                    scope.launch { settings.setRetention(choice) }
                                }
                            },
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
