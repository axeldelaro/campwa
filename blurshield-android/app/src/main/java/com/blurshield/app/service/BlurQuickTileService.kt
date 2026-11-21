package com.blurshield.app.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.blurshield.app.config.BlurSettings

@RequiresApi(Build.VERSION_CODES.N)
class BlurQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val settings = BlurSettings(applicationContext)
        settings.enabled = !settings.enabled
        syncTile()
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val settings = BlurSettings(applicationContext)
        val active = BlurAccessibilityService.isServiceActive() && settings.enabled
        tile.state = when {
            !BlurAccessibilityService.isServiceActive() -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = if (active) "BlurShield ON" else "BlurShield"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !BlurAccessibilityService.isServiceActive() -> "Accessibilité requise"
                TurboCaptureService.isRunning -> "Turbo 30Hz"
                active -> "Actif"
                else -> "En pause"
            }
        }
        tile.updateTile()
    }
}
