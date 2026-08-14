package com.warmly.watchrecorder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import com.warmly.watchrecorder.data.RecorderSettings
import com.warmly.watchrecorder.data.Retention
import com.warmly.watchrecorder.service.RecorderBus
import com.warmly.watchrecorder.service.RecorderService
import kotlinx.coroutines.launch

private enum class Screen { Main, Settings }

@Composable
fun RecorderApp(initiallyGranted: Boolean, requiredPermissions: Array<String>) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(initiallyGranted) }
    var screen by remember { mutableStateOf(Screen.Main) }

    val scope = rememberCoroutineScope()
    val settings = remember { RecorderSettings(context) }
    val retention by settings.retention.collectAsStateWithLifecycle(Retention.DEFAULT)
    val capture by RecorderBus.capture.collectAsStateWithLifecycle()
    val pending by RecorderBus.pendingUploads.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        granted = results[android.Manifest.permission.RECORD_AUDIO] ?: granted
    }

    LaunchedEffect(granted) {
        if (!granted) permissionLauncher.launch(requiredPermissions)
    }

    MaterialTheme {
        when (screen) {
            Screen.Main -> RecorderScreen(
                state = capture,
                retention = retention,
                pendingUploads = pending,
                micGranted = granted,
                onToggleRecording = {
                    if (capture.recording) RecorderService.stop(context) else RecorderService.start(context)
                },
                onSave = { RecorderService.save(context) },
                onOpenSettings = { screen = Screen.Settings },
                onRequestPermission = { permissionLauncher.launch(requiredPermissions) },
            )

            Screen.Settings -> RetentionScreen(
                selected = retention,
                onSelect = { choice ->
                    if (capture.recording) {
                        // Live change: the service resizes the ring and persists it.
                        RecorderService.setRetention(context, choice)
                    } else {
                        scope.launch { settings.setRetention(choice) }
                    }
                    screen = Screen.Main
                },
            )
        }
    }
}
