package com.newax.aegis

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class CrashReporterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorText = intent.getStringExtra("CRASH_LOG") ?: "Unknown Error"
        
        val textView = TextView(this).apply {
            text = "AEGIS CRASHED:\n\n$errorText"
            setTextColor(android.graphics.Color.RED)
            textSize = 14f
            setPadding(32, 32, 32, 32)
        }
        
        val scrollView = android.widget.ScrollView(this).apply {
            addView(textView)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        
        setContentView(scrollView)
    }
}
