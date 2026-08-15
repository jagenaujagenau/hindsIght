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
import earth.diego.hindsight.service.RecorderService

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
        const val ID_SAVE = "save"
        const val ID_TOGGLE = "toggle"

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
        // A LoadAction tap comes back as another tile request carrying the id of
        // whatever was clicked — that is where the work happens, so the tap never
        // has to launch an activity.
        when (requestParams.currentState.lastClickableId) {
            ID_SAVE -> RecorderService.save(this)
            ID_TOGGLE -> RecorderService.start(this)
        }

        val state = RecorderBus.capture.value
        val pending = RecorderBus.pendingUploads.value

        val headline = if (state.recording) formatDuration(state.bufferedMs) else "Not listening"
        val caption = when {
            state.recording -> "buffered"
            pending > 0 -> "$pending waiting for phone"
            else -> "Tap to start"
        }

        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(text(headline, 30f, primary = true))
                    .addContent(text(caption, 13f, primary = false))
                    .addContent(spacer())
                    .addContent(
                        if (state.recording) {
                            actionChip("SAVE", ID_SAVE)
                        } else {
                            actionChip("START", ID_TOGGLE)
                        },
                    )
                    .build(),
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layout).build())
                            .build(),
                    )
                    .build(),
            )
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

    /**
     * Taps arrive here rather than launching an activity, so saving never puts a
     * screen between the user and the recording.
     */
    override fun onTileEnterEvent(requestParams: androidx.wear.tiles.EventBuilders.TileEnterEvent) {
        getUpdater(this).requestUpdate(SaveTileService::class.java)
    }

    private fun text(value: String, sizeSp: Float, primary: Boolean) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
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
                                ActionBuilders.LoadAction.Builder().build(),
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

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
