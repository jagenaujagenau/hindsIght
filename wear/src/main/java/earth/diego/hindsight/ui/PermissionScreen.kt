package earth.diego.hindsight.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

internal fun needsPermissionSettings(requested: Boolean, granted: Boolean, showRationale: Boolean) =
    requested && !granted && !showRationale

@Composable
internal fun PermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit, onSettings: () -> Unit) {
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text("Microphone access") } }
            item { Text(if (permanentlyDenied) "Enable microphone permission in app settings, then return here."
                else "Hindsight needs microphone access to keep a rolling audio buffer on this watch.") }
            if (!permanentlyDenied) item {
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Allow microphone") }
            }
            item { Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Open settings") } }
        }
    }
}
