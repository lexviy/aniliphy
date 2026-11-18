package com.aniliphy

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aniliphy.core.Incognito

/** Incognito as a quick-settings tile, one tap from anywhere. */
class IncognitoTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        Incognito.init(this)
        Incognito.set(this, !Incognito.on)
        show()
    }

    private fun show() {
        Incognito.init(this)
        val tile = qsTile ?: return
        tile.state = if (Incognito.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Incognito"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = if (Incognito.on) "Nothing kept" else "Off"
        }
        tile.updateTile()
    }
}
