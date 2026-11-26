package com.nnexe.overlaybrowser

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** Quick Settings tile: one tap shows or hides the floating console from anywhere, even mid-game. */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (OverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (!Settings.canDrawOverlays(this)) {
            openSettings()
            return
        }
        try {
            OverlayService.toggle(this)
        } catch (e: Exception) {
            Log.w(App.TAG, "Couldn't start overlay from tile", e)
            openSettings()
        }
    }

    @Suppress("DEPRECATION")
    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
