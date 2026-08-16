package earth.diego.hindsight.tile

import android.app.Activity
import android.os.Bundle
import android.util.Log
import earth.diego.hindsight.service.RecorderService

/**
 * Performs a tile tap, then disappears.
 *
 * Tile actions must not be run from `onTileRequest`. That callback is a *render*,
 * and the clicked id it carries is sticky state: the system re-requests a tile on
 * its freshness interval and whenever it is scrolled into view, so acting on the
 * id there repeats the action indefinitely after a single tap. Routing through a
 * launched activity makes the tap fire exactly once, because launching is the
 * event.
 *
 * Has no UI and finishes immediately, so nothing is drawn.
 */
class TileActionActivity : Activity() {

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_SAVE = "save"
        const val ACTION_START = "start"

        private const val TAG = "TileAction"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_SAVE -> {
                Log.i(TAG, "Tile: save")
                RecorderService.save(this)
            }

            ACTION_START -> {
                Log.i(TAG, "Tile: start")
                RecorderService.start(this)
            }

            else -> Log.w(TAG, "Tile action with no recognised extra")
        }
        finish()
    }
}
