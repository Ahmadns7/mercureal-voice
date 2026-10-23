package com.mercureal.voice

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile: toggles the flashlight. Add it from the quick settings edit panel. */
class FlashlightTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val r = Actions.flashlight(applicationContext, null)
        Store.addHistory(applicationContext, "flashlight (tile)", r.speech, r.ok)
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (Actions.isTorchOn()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}

/** Quick settings tile: takes a screenshot. The first use asks for screen capture approval. */
class ScreenshotTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val app = applicationContext
        if (!ScreenshotCapture.hasGrant()) {
            val i = Intent(this, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        ScreenshotCapture.capture(app) { ok, msg ->
            Store.addHistory(app, "screenshot (tile)", msg, ok)
            VoiceService.say(app, msg)
        }
    }
}
