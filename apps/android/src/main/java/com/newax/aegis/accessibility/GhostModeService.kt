package com.newax.aegis.accessibility

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Draws a pitch-black overlay over the entire screen to obscure the UI while
 * the Accessibility Service performs background automated tasks.
 */
class GhostModeService : Service() {
    private var windowManager: WindowManager? = null
    private var ghostView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        ghostView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // Add a small subtle text so the user knows it's Newax, not a broken screen
            val text = android.widget.TextView(this@GhostModeService).apply {
                text = "Newax Ghost Mode Active"
                setTextColor(Color.DKGRAY)
                textSize = 14f
            }
            addView(text, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(ghostView, params)
        } catch (e: Exception) {
            // Permission likely not granted
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ghostView != null) {
            try {
                windowManager?.removeView(ghostView)
            } catch (e: Exception) {}
            ghostView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
