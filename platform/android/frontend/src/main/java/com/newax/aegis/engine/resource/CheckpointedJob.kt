package com.newax.aegis.engine.resource

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class CheckpointedJob<T>(
    private val context: Context,
    private val jobKey: String,
    private val batchSize: Int = 100,
    private val items: List<T>,
    private val processItem: suspend (T) -> Unit
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("aegis_checkpoints", Context.MODE_PRIVATE)
    }

    private fun savedOffset(): Int = prefs.getInt("${jobKey}_offset", 0)
    private fun saveOffset(n: Int) = prefs.edit().putInt("${jobKey}_offset", n).apply()
    fun reset() = prefs.edit().remove("${jobKey}_offset").apply()

    suspend fun run(onYield: suspend () -> Boolean = { true }) {
        var offset = savedOffset()
        while (offset < items.size) {
            if (!coroutineContext.isActive) break
            if (!onYield()) break
            val batch = items.subList(offset, minOf(offset + batchSize, items.size))
            batch.forEach { if (coroutineContext.isActive) processItem(it) }
            offset += batch.size
            saveOffset(offset)
        }
        if (offset >= items.size) reset()
    }
}
