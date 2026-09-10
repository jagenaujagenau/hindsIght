package earth.diego.hindsight.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.foundation.pager.HorizontalPager
import earth.diego.hindsight.data.RecorderSettings
import earth.diego.hindsight.data.Appearance
import earth.diego.hindsight.data.SessionLimit
import earth.diego.hindsight.sync.ClipOutbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import earth.diego.hindsight.sync.SyncState
import kotlinx.coroutines.withContext
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
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var stopOptionsOpen by rememberSaveable { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }
    BackHandler(stopOptionsOpen) { stopOptionsOpen = false }
    var pendingStartRequest by rememberSaveable { mutableStateOf(startRequested) }
    val settings = remember { RecorderSettings(context) }
    val appearance by settings.appearance.collectAsStateWithLifecycle(Appearance())
    val sessionLimit by settings.sessionLimit.collectAsStateWithLifecycle(SessionLimit.OFF)
    val showHints by settings.showHints.collectAsStateWithLifecycle(true)
    val storage by RecorderBus.storage.collectAsStateWithLifecycle()
    val sync by RecorderBus.sync.collectAsStateWithLifecycle()
    val battery by RecorderBus.battery.collectAsStateWithLifecycle()
    val sessionNotice by RecorderBus.sessionNotice.collectAsStateWithLifecycle()
    val save by RecorderBus.save.collectAsStateWithLifecycle()
    val pending by RecorderBus.pendingUploads.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ClipOutbox.refreshStatus(context) } }
    // The app shell needs transitions, not the once-per-second buffer counter.
    val recordingFlow = remember { RecorderBus.capture.map { it.recording }.distinctUntilChanged() }
    val recording by recordingFlow.collectAsStateWithLifecycle(false)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        granted = results[Manifest.permission.RECORD_AUDIO] ?: granted
        permanentlyDenied = needsPermissionSettings(permissionRequested, granted,
            activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } == true)
    }

    // Settings and permission revocation can change access without recreating this activity.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permanentlyDenied = needsPermissionSettings(permissionRequested, granted,
            activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } == true)
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
                PermissionScreen(
                    permanentlyDenied = permanentlyDenied,
                    onRequest = { permissionRequested = true; permissionLauncher.launch(requiredPermissions) },
                    onSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                )
                return@AppScaffold
            }
            if (stopOptionsOpen) {
                StopOptionsScreen(
                    onSaveStop = { stopOptionsOpen = false; RecorderService.saveAndStop(context) },
                    onDiscard = { stopOptionsOpen = false; RecorderService.stop(context) },
                    onCancel = { stopOptionsOpen = false },
                )
                return@AppScaffold
            }

            // Swipe left for recording controls, then Appearance for visual choices.
            val pagerState = rememberPagerState { 2 }

            HorizontalPagerScaffold(pagerState = pagerState) {
                HorizontalPager(state = pagerState) { page ->
                    when (page) {
                        PAGE_WAVE -> {
                            val capture by RecorderBus.capture.collectAsStateWithLifecycle()
                            WaveScreen(
                                state = capture,
                                save = save,
                                pendingUploads = pending,
                                visible = pagerState.currentPage == PAGE_WAVE,
                                showHints = showHints,
                                sync = sync,
                                warning = storage?.warning ?: if (capture.recording && battery?.low == true) "Low battery · ${battery?.percent}% remaining" else null,
                                sessionNotice = sessionNotice,
                                style = appearance.waveStyle,
                                accent = appearance.accent.color ?: MaterialTheme.colorScheme.primary,
                                sensitivity = appearance.sensitivity,
                                onSave = { RecorderService.save(context) },
                                onStop = { stopOptionsOpen = true },
                                onResume = { RecorderService.start(context) },
                            )
                        }

                        PAGE_SETTINGS -> SettingsScreen(
                            recording = recording,
                            onToggleRecording = {
                                if (recording) stopOptionsOpen = true else RecorderService.start(context)
                            },
                            onSaveStop = { RecorderService.saveAndStop(context) },
                            save = save,
                            sessionNotice = sessionNotice,
                            sessionLimit = sessionLimit,
                            onTimer = { RecorderService.setTimer(context, it) },
                            storage = storage,
                            sync = sync,
                            pendingUploads = pending,
                            onSync = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { ClipOutbox.refreshStatus(context); ClipOutbox.enqueueUpload(context) }
                                    } catch (t: CancellationException) {
                                        throw t
                                    } catch (t: Exception) {
                                        RecorderBus.publishSync(SyncState.Retry("Could not schedule sync. Reopen the app and retry."))
                                    }
                                }
                            },
                            onHints = { scope.launch { settings.resetHints() } },
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
