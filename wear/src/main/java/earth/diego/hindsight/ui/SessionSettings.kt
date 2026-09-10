package earth.diego.hindsight.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import earth.diego.hindsight.data.SessionLimit
import earth.diego.hindsight.service.StorageStatus
import earth.diego.hindsight.sync.SyncState

@Composable
internal fun TimerSettings(limit: SessionLimit, onSelect: (SessionLimit) -> Unit, notice: String?, onBack: () -> Unit) {
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Recording settings") } }
            item { ListHeader { Text("Save buffer & stop after") } }
            if (notice != null) item { Text(notice) }
            item { Text("Saves only your selected retention window, not the whole session. If saving fails, listening continues with a warning.") }
            items(SessionLimit.entries, key = { it.name }) { option ->
                RadioButton(selected = option == limit, onSelect = { onSelect(option) },
                    modifier = Modifier.fillMaxWidth(), label = { Text(option.label) })
            }
            item { Text("Changing this while listening restarts the countdown.") }
        }
    }
}

@Composable
internal fun SyncSettings(storage: StorageStatus?, sync: SyncState, pending: Int, onSync: () -> Unit, onBack: () -> Unit) {
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Recording settings") } }
            item { ListHeader { Text("Sync & storage") } }
            item { Text(syncMessage(sync, pending) ?: "Nothing waiting to sync") }
            if (sync is SyncState.Retry && pending > 0) item { Text(sync.message) }
            item {
                Button(onClick = onSync, enabled = pending > 0 && sync !is SyncState.Sending && sync != SyncState.Checking,
                    modifier = Modifier.fillMaxWidth()) { Text("Retry sync") }
            }
            item { Text("Phone unavailable? Connect your watch and open Hindsight on your phone.") }
            if (storage != null) {
                item { Text("${formatStorageBytes(storage.pendingBytes)} saved on watch\n${formatStorageBytes(storage.freeBytes)} free") }
                storage.warning?.let { warning -> item { Text(warning) } }
            }
            item { Text("Saved audio is never silently deleted. Space is reclaimed only after your phone confirms delivery.") }
        }
    }
}
