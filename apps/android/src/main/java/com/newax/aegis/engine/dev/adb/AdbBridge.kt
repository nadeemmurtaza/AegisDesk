package com.newax.aegis.engine.dev.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.engine.dev.ci.HeadlessCi
import com.newax.aegis.engine.dev.log.NewaxLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdbBridge : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.newax.aegis.DEV_CMD"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ARG1 = "arg1"
        const val EXTRA_ARG2 = "arg2"
        const val EXTRA_ARG3 = "arg3"
        const val TAG = "AdbBridge"

        private val scope = CoroutineScope(Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
        val arg1 = intent.getStringExtra(EXTRA_ARG1) ?: ""
        val arg2 = intent.getStringExtra(EXTRA_ARG2) ?: ""
        val arg3 = intent.getStringExtra(EXTRA_ARG3) ?: ""

        Log.d(TAG, "ADB CMD: $cmd arg1=$arg1 arg2=$arg2 arg3=$arg3")
        NewaxLogger.i(TAG, "ADB CMD: $cmd", module = "AdbBridge")

        scope.launch {
            val db = getDatabase(context)
            val result = HeadlessCi.execute(context, db, cmd, arg1, arg2, arg3)
            Log.d(TAG, "ADB RESULT: $result")
            NewaxLogger.i(TAG, "ADB RESULT [$cmd]: $result", module = "AdbBridge")

            val resultIntent = Intent("${ACTION}_RESULT").apply {
                setPackage(context.packageName)
                putExtra("cmd", cmd)
                putExtra("result", result)
                putExtra("timestampMs", System.currentTimeMillis())
            }
            context.sendBroadcast(resultIntent)
        }
    }

    private fun getDatabase(context: Context): NewaxDatabase? = try {
        NewaxDatabase.get
    } catch (_: Exception) { null }
}
