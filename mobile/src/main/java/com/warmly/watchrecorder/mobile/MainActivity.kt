package com.warmly.watchrecorder.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.warmly.watchrecorder.mobile.data.ClipStore
import com.warmly.watchrecorder.mobile.player.ClipPlayer
import com.warmly.watchrecorder.mobile.ui.ClipListScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val player = ClipPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current

            // Only affects the "clip arrived" notification; the transfer itself
            // works whether or not this is granted.
            val notifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* best-effort */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            MaterialTheme(
                colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicDarkColorScheme(context)
                } else {
                    darkColorScheme()
                },
            ) {
                ClipListScreen(player = player)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The receiver service may have landed clips while we were away.
        lifecycleScope.launch { ClipStore.refresh(this@MainActivity) }
    }

    override fun onStop() {
        super.onStop()
        player.stop()
    }
}
