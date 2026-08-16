package earth.diego.hindsight.mobile.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.diego.hindsight.mobile.data.Clip
import earth.diego.hindsight.mobile.data.ClipStore
import earth.diego.hindsight.mobile.ui.components.WaveformView
import earth.diego.hindsight.mobile.ui.theme.hindsight

/** Width of the time axis gutter. Every row hangs off this line. */
private val AXIS = 72.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.LibraryScreen(
    clips: List<Clip>,
    animatedVisibilityScope: AnimatedContentScope,
    playingClipId: String?,
    snackbarHostState: SnackbarHostState,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onOpen: (Clip) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    val filtered = remember(clips, query) {
        if (query.isBlank()) {
            clips
        } else {
            clips.filter {
                it.displayTitle.contains(query, ignoreCase = true) ||
                    it.transcript?.contains(query, ignoreCase = true) == true
            }
        }
    }
    val rows = remember(filtered) { Timeline.build(filtered) }

    Scaffold(
        containerColor = hindsight.paper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopBar(searching, query, clips.isNotEmpty(), { query = it }) { searching = it } },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = onSync,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                clips.isEmpty() -> Empty(
                    "Nothing yet",
                    "Tap the wave on your watch. Whatever it just heard lands here.",
                )

                rows.isEmpty() -> Empty("No matches", "Nothing here mentions “$query”.")

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 48.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is TimelineRow.Day -> DayMark(row.label)
                            is TimelineRow.Quiet -> QuietStretch(row.hours)
                            is TimelineRow.Moment -> Moment(
                                clip = row.clip,
                                hour = row.hour,
                                isPlaying = row.clip.id == playingClipId,
                                animatedVisibilityScope = animatedVisibilityScope,
                                onClick = { onOpen(row.clip) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    searching: Boolean,
    query: String,
    hasClips: Boolean,
    onQuery: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // The app draws edge to edge, and this replaced a TopAppBar, which was
            // the thing that used to inset for the status bar.
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = LocalTextStyle.current
                    .merge(MaterialTheme.typography.headlineSmall)
                    .copy(color = hindsight.graphite),
                cursorBrush = SolidColor(hindsight.signal),
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    if (query.isEmpty()) {
                        Text(
                            "Search names and speech",
                            style = MaterialTheme.typography.headlineSmall,
                            color = hindsight.muted,
                        )
                    }
                    field()
                },
            )
            IconButton(onClick = { onSearching(false); onQuery("") }) {
                Icon(Icons.Filled.Close, "Close search", tint = hindsight.muted)
            }
        } else {
            Text(
                "Hindsight",
                style = MaterialTheme.typography.headlineSmall,
                color = hindsight.graphite,
                modifier = Modifier.weight(1f),
            )
            if (hasClips) {
                IconButton(onClick = { onSearching(true) }) {
                    Icon(Icons.Filled.Search, "Search", tint = hindsight.muted)
                }
            }
        }
    }
}

@Composable
private fun DayMark(label: String) {
    val rule = hindsight.rule
    Column {
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = hindsight.graphite,
                modifier = Modifier.padding(start = 20.dp, end = 12.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .drawBehind { drawRect(rule) },
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * The hours nothing was recorded, drawn rather than skipped.
 *
 * A day is mostly silence, and a list that quietly closes those gaps reads as
 * though the app were always catching something. Showing them is what makes this
 * a record of a day rather than a folder of files.
 */
@Composable
private fun QuietStretch(hours: Int) {
    val rule = hindsight.rule
    Row(Modifier.height(56.dp)) {
        Box(
            Modifier
                .width(AXIS)
                .fillMaxSize()
                .drawBehind {
                    val x = size.width - 20.dp.toPx()
                    drawLine(
                        color = rule,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(2.dp.toPx(), 7.dp.toPx()),
                        ),
                    )
                },
        )
        Text(
            if (hours == 1) "1 quiet hour" else "$hours quiet hours",
            style = MaterialTheme.typography.labelSmall,
            color = hindsight.muted,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.Moment(
    clip: Clip,
    hour: String?,
    isPlaying: Boolean,
    animatedVisibilityScope: AnimatedContentScope,
    onClick: () -> Unit,
) {
    val peaks = rememberPeaks(clip)
    val rule = hindsight.rule
    val signal = hindsight.signal

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(86.dp),
    ) {
        Box(
            Modifier
                .width(AXIS)
                .fillMaxSize()
                .drawBehind {
                    val x = size.width - 20.dp.toPx()
                    drawLine(rule, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    // A short tick joins the clip to the hour it belongs to.
                    if (hour != null) {
                        drawLine(
                            rule,
                            Offset(x, size.height / 2f),
                            Offset(size.width, size.height / 2f),
                            1.dp.toPx(),
                        )
                    }
                    if (isPlaying) {
                        drawCircle(signal, 3.5.dp.toPx(), Offset(x, size.height / 2f))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (hour != null) {
                Text(
                    hour,
                    style = MaterialTheme.typography.displaySmall,
                    color = hindsight.graphite,
                    modifier = Modifier.padding(start = 18.dp),
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(end = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // The waveform is the clip's identity, and literally the same object
            // the player grows from — hence the shared key.
            WaveformView(
                peaks = peaks,
                progress = 0f,
                onSeek = {},
                interactive = false,
                height = 42.dp,
                barCount = 56,
                modifier = Modifier.sharedElement(
                    rememberSharedContentState(key = "wave-${clip.id}"),
                    animatedVisibilityScope,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clip.displayTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPlaying) hindsight.signal else hindsight.muted,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    ClipStore.formatDuration(clip.durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = hindsight.muted,
                )
                if (clip.transcript != null) {
                    Spacer(Modifier.width(12.dp))
                    Text("TEXT", style = MaterialTheme.typography.labelSmall, color = hindsight.trace)
                }
            }
        }
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = hindsight.graphite)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = hindsight.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
