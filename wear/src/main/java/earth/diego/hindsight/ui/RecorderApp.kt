package earth.diego.hindsight.ui

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
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import earth.diego.hindsight.data.RecorderSettings
import earth.diego.hindsight.data.Retention
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.RecorderService
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val RETENTION = "retention"
}

@Composable
fun RecorderApp(initiallyGranted: Boolean, requiredPermissions: Array<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController: NavHostController = rememberSwipeDismissableNavController()

    var granted by remember { mutableStateOf(initiallyGranted) }
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

    HindsightTheme {
        // AppScaffold owns TimeText across destinations so it survives navigation
        // rather than being rebuilt per screen.
        AppScaffold {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = Routes.HOME,
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        state = capture,
                        retention = retention,
                        pendingUploads = pending,
                        micGranted = granted,
                        onToggleRecording = {
                            if (capture.recording) {
                                RecorderService.stop(context)
                            } else {
                                RecorderService.start(context)
                            }
                        },
                        onSave = { RecorderService.save(context) },
                        onOpenSettings = { navController.navigate(Routes.RETENTION) },
                        onRequestPermission = { permissionLauncher.launch(requiredPermissions) },
                    )
                }

                composable(Routes.RETENTION) {
                    RetentionScreen(
                        selected = retention,
                        recording = capture.recording,
                        onStop = {
                            RecorderService.stop(context)
                            navController.popBackStack()
                        },
                        onSelect = { choice ->
                            if (capture.recording) {
                                // Live change: the service resizes the ring and persists it.
                                RecorderService.setRetention(context, choice)
                            } else {
                                scope.launch { settings.setRetention(choice) }
                            }
                            navController.popBackStack()
                        },
                    )
                }
            }
        }
    }
}
