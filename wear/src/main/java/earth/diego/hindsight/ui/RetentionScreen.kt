package earth.diego.hindsight.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import earth.diego.hindsight.data.Retention

@Composable
fun RetentionScreen(
    selected: Retention,
    recording: Boolean,
    onSelect: (Retention) -> Unit,
    onStop: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                ListHeader { Text("How far back?") }
            }
            items(Retention.entries.toList()) { option ->
                RadioButton(
                    selected = option == selected,
                    onSelect = { onSelect(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    label = { Text(option.label) },
                    // Storage is the real trade-off behind this choice, so name it.
                    secondaryLabel = {
                        Text(
                            "~%.1f MB".format(option.approxStorageMb),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            // Stopping is rare and clears the buffer, so it lives here rather than
            // competing for space with Save on the main screen.
            if (recording) {
                item {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Text("Stop listening")
                    }
                }
            }
        }
    }
}
