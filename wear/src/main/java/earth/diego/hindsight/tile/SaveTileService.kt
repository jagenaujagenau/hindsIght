package earth.diego.hindsight.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.concurrent.futures.ResolvableFuture
import com.google.common.util.concurrent.ListenableFuture
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.SaveState
import earth.diego.hindsight.ui.saveMessage
import earth.diego.hindsight.ui.formatBufferDuration
import earth.diego.hindsight.ui.confirmationRemainingMs
import earth.diego.hindsight.ui.syncMessage

/**
 * A tile whose entire job is one tap.
 *
 * The moment you want to save is the moment you least want to hunt for an app:
 * something just happened and you have seconds. From the watch face this is one
 * swipe and one tap, with no launch and no screen to read.
 */
class SaveTileService : TileService() {

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val ID_SAVE = TileActionActivity.ACTION_SAVE
        const val ID_TOGGLE = TileActionActivity.ACTION_START

        /**
         * Tiles are snapshots, not live views. A short freshness interval keeps the
         * buffered figure roughly honest without waking us constantly; the tile is
         * still useful when stale because the button works regardless.
         */
        const val FRESHNESS_MS = 60_000L
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        // Deliberately no side effects here. onTileRequest is a render, called on
        // the freshness interval and whenever the tile scrolls into view, and the
        // clicked id it carries is sticky — acting on it repeated a single tap
        // forever. Taps go through TileActionActivity instead.

        val state = RecorderBus.capture.value
        val pending = RecorderBus.pendingUploads.value

        val save = RecorderBus.save.value
        val headline = when {
            state.error != null -> "Interrupted"
            state.starting -> "Starting…"
            state.recording -> formatBufferDuration(state.bufferedMs)
            else -> "Not listening"
        }
        val fallbackCaption = if (state.error != null) "Open app to retry" else
            RecorderBus.sessionNotice.value ?:
            saveMessage(if (save is SaveState.Saved) SaveState.Idle else save, 0) ?:
            RecorderBus.storage.value?.warning ?:
            syncMessage(RecorderBus.sync.value, pending) ?:
            if (state.recording && RecorderBus.battery.value?.low == true) "Low battery · open app"
            else if (state.recording) "buffered · tap to save" else "Tap to start"

        fun layoutFor(caption: String) = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setPadding(ModifiersBuilders.Padding.Builder()
                    .setAll(androidx.wear.protolayout.DimensionBuilders.dp(24f)).build())
                .build())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(text(headline, 30f, primary = true))
                    .addContent(text(caption, 13f, primary = false))
                    .addContent(spacer())
                    .addContent(
                        if (state.recording) {
                            actionChip(if (save == SaveState.Saving) "SAVING…" else "SAVE", ID_SAVE)
                        } else {
                            actionChip("START", ID_TOGGLE)
                        },
                    )
                    .build(),
            )
            .build()

        val now = System.currentTimeMillis()
        val remaining = if (save is SaveState.Saved) confirmationRemainingMs(save, now) else 0L
        val timeline = TimelineBuilders.Timeline.Builder()
        val fallback = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layoutFor(fallbackCaption)).build())
        if (remaining > 0) {
            val expires = now + remaining
            timeline.addTimelineEntry(TimelineBuilders.TimelineEntry.Builder()
                .setValidity(TimelineBuilders.TimeInterval.Builder().setStartMillis(now).setEndMillis(expires).build())
                .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layoutFor(saveMessage(save, 0)!!)).build())
                .build())
            fallback.setValidity(TimelineBuilders.TimeInterval.Builder().setStartMillis(expires).setEndMillis(Long.MAX_VALUE).build())
        }
        timeline.addTimelineEntry(fallback.build())
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            // The renderer expires confirmation locally; no five-second wakeup is needed.
            .setTileTimeline(timeline.build())
            .build()

        return immediate(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = immediate(
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
    )

    /** Both tile callbacks are pure computation, so the future is already resolved. */
    private fun <T> immediate(value: T): ListenableFuture<T> =
        ResolvableFuture.create<T>().apply { set(value) }

    /** Refresh the snapshot on entry; tap actions are handled by TileActionActivity. */
    override fun onTileEnterEvent(requestParams: androidx.wear.tiles.EventBuilders.TileEnterEvent) {
        getUpdater(this).requestUpdate(SaveTileService::class.java)
    }

    private fun text(value: String, sizeSp: Float, primary: Boolean) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(if (primary) 1 else 3)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(androidx.wear.protolayout.DimensionBuilders.sp(sizeSp))
                    .setColor(argb(if (primary) 0xFFFFFFFF.toInt() else 0xFFB0B0B8.toInt()))
                    .build(),
            )
            .build()

    private fun spacer() = LayoutElementBuilders.Spacer.Builder()
        .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(8f))
        .build()

    private fun actionChip(label: String, id: String) =
        LayoutElementBuilders.Box.Builder()
            .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(52f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFFD0BCFF.toInt()))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(androidx.wear.protolayout.DimensionBuilders.dp(26f))
                                    .build(),
                            )
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(androidx.wear.protolayout.DimensionBuilders.dp(14f))
                            .build(),
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(id)
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(TileActionActivity::class.java.name)
                                            .addKeyToExtraMapping(
                                                TileActionActivity.EXTRA_ACTION,
                                                ActionBuilders.AndroidStringExtra.Builder()
                                                    .setValue(id)
                                                    .build(),
                                            )
                                            .build(),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(androidx.wear.protolayout.DimensionBuilders.sp(16f))
                            .setColor(argb(0xFF1C1B1F.toInt()))
                            .build(),
                    )
                    .build(),
            )
            .build()

}
