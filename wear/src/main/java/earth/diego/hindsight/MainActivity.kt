package earth.diego.hindsight

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import earth.diego.hindsight.ui.RecorderApp

class MainActivity : ComponentActivity() {

    companion object { const val EXTRA_START_LISTENING = "start_listening" }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        setContent {
            RecorderApp(
                startRequested = intent.getBooleanExtra(EXTRA_START_LISTENING, false),
                initiallyGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
                requiredPermissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray(),
            )
        }
    }
}
