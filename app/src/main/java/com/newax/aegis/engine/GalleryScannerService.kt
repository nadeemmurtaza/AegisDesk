package com.newax.aegis.engine

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A background service that securely scans the user's local gallery on demand.
 */
class GalleryScannerService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("AegisScanner", "Starting On-Demand Gallery Scan...")
        serviceScope.launch {
            scanGallery()
        }
        return START_NOT_STICKY
    }

    private fun scanGallery() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        // Query the last 50 images
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use {
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            
            var count = 0
            while (it.moveToNext() && count < 50) {
                val path = it.getString(dataColumn)
                Log.d("AegisScanner", "Scanning: $path")
                
                try {
                    val bitmap = BitmapFactory.decodeFile(path)
                    if (bitmap != null) {
                        val prompt = "[Gallery Scan: $path] Analyze this image visually. If it's blurry or junk, output 'delete file $path'. Otherwise, output 'Looks good'."
                        TriggerEngine.triggerEvents.tryEmit(prompt)
                    }
                } catch (e: Exception) {
                    Log.e("AegisScanner", "Failed to decode image at $path")
                }
                count++
            }
        }
        Log.i("AegisScanner", "On-Demand Gallery Scan Completed.")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
