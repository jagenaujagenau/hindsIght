package earth.diego.hindsight.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** The warning is adjacent to an explicit discard action, not hidden in a gesture. */
@Composable
internal fun StopOptionsScreen(onSaveStop: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text("Stop listening?") } }
            item { Text("Stopping clears unsaved audio. Saved clips are kept.") }
            item { Button(onClick = onSaveStop, modifier = Modifier.fillMaxWidth()) { Text("Save & stop") } }
            item { Button(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Stop without saving") } }
            item { Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Keep listening") } }
        }
    }
}
