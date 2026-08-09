package com.newax.aegis.vision

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class AegisQSTileService : TileService() {
    companion object {
        private const val TAG = "AegisQSTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        // If ScreenCaptureService is running, tile is Active, else Inactive
        val isRunning = ScreenCaptureService.instance != null
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked")
        
        val isRunning = ScreenCaptureService.instance != null
        if (isRunning) {
            // Trigger a single capture
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_UNDERSTAND_SCREEN
            }
            startService(intent)
        } else {
            // Prompt the user to open the app and start screen capture first
            // Note: in a real app, we might launch MainActivity to request permission
            Log.d(TAG, "ScreenCaptureService not running. Cannot capture.")
        }
    }
}
