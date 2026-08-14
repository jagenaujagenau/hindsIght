package com.warmly.watchrecorder.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.warmly.watchrecorder.data.Retention

@Composable
fun RetentionScreen(
    selected: Retention,
    onSelect: (Retention) -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item {
                ListHeader { Text("Save last", style = MaterialTheme.typography.caption1) }
            }
            items(Retention.entries) { option ->
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(option) },
                    label = { Text(option.label) },
                    // Storage cost is the real tradeoff behind this choice, so show it.
                    secondaryLabel = { Text("~%.1f MB".format(option.approxStorageMb)) },
                    colors = if (option == selected) {
                        ChipDefaults.primaryChipColors()
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                )
            }
        }
    }
}
