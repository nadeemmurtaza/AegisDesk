package com.newax.aegis.engine.dev.ws

import android.content.Context
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.engine.dev.db.DatabaseInspector
import com.newax.aegis.engine.dev.log.NewaxLogger
import com.newax.aegis.engine.dev.profiler.ResourceProfiler
import com.newax.aegis.engine.dev.trace.DecisionInspector
import com.newax.aegis.engine.metrics.MetricsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

object DebugWebSocketServer {

    const val DEFAULT_PORT = 7878
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var appContext: Context? = null
    private var database: NewaxDatabase? = null

    fun start(context: Context, db: NewaxDatabase, port: Int = DEFAULT_PORT) {
        if (running.getAndSet(true)) return
        appContext = context.applicationContext
        database = db
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                NewaxLogger.i("DebugServer", "HTTP debug server started on port $port")
                while (running.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        scope.launch { handleClient(client) }
                    } catch (_: Exception) { if (!running.get()) break }
                }
            } catch (e: Exception) {
                NewaxLogger.e("DebugServer", "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)

            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
                line = reader.readLine()
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }

            val body = when {
                path == "/" || path == "/ping" -> json("status", "ok", "server", "NewaxDebug/1.0")
                path == "/metrics" -> MetricsEngine.summary().let { json("metrics", it) }
                path == "/logs" -> jsonArray("logs", NewaxLogger.recent(50).map { e -> "${e.level.name} [${e.tag}] ${e.message}" })
                path == "/traces" -> jsonArray("traces", DecisionInspector.recent(10).map { t -> "${t.id}: ${t.queryText.take(40)} ok=${t.success}" })
                path.startsWith("/query") -> handleQuery(path)
                path == "/db/stats" -> handleDbStats()
                path == "/resources" -> handleResources()
                path == "/health" -> healthCheck()
                else -> json("error", "unknown_path", "path", path)
            }

            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: application/json")
            writer.println("Content-Length: ${body.length}")
            writer.println("Access-Control-Allow-Origin: *")
            writer.println()
            writer.println(body)
            writer.flush()
        } catch (e: Exception) {
            NewaxLogger.w("DebugServer", "Client error: ${e.message}")
        } finally {
            client.close()
        }
    }

    private fun handleQuery(path: String): String {
        val sql = path.substringAfter("/query?sql=").replace("%20", " ").replace("+", " ")
        val db = database ?: return json("error", "db_not_initialized")
        val result = runBlocking { DatabaseInspector.rawQuery(db, sql, 50) }
        return if (result.error != null) json("error", result.error ?: "unknown") else buildString {
            append("{\"columns\":[")
            append(result.columns.joinToString(",") { "\"$it\"" })
            append("],\"rows\":[")
            append(result.rows.joinToString(",") { row -> "[${row.joinToString(",") { "\"${it.replace("\"", "'")}\"" }}]" })
            append("],\"count\":${result.rowCount}}")
        }
    }

    private fun handleDbStats(): String {
        val db = database ?: return json("error", "db_not_initialized")
        val stats = runBlocking { DatabaseInspector.tableStats(db) }
        return "{\"tables\":{${stats.joinToString(",") { "\"${it.name}\":${it.rowCount}" }}}}"
    }

    private fun handleResources(): String {
        val ctx = appContext ?: return json("error", "context_null")
        val snap = ResourceProfiler.snapshot(ctx)
        return buildString {
            append("{\"ram_used_mb\":${snap.ramUsedMb},\"ram_total_mb\":${snap.ramTotalMb},")
            append("\"battery\":${snap.batteryPercent},\"pressure\":${snap.pressureLevel},")
            append("\"heavy_active\":${snap.heavyWorkerActive},\"queue\":${snap.queueDepth}}")
        }
    }

    private fun healthCheck(): String {
        val db = database
        val dbOk = db != null && runCatching { db.openHelper.readableDatabase != null }.getOrDefault(false)
        return json("status", if (dbOk) "healthy" else "degraded", "db", dbOk.toString())
    }

    private fun json(vararg pairs: String): String {
        val entries = pairs.toList().chunked(2).joinToString(",") { (k, v) -> "\"$k\":\"${v.replace("\"", "'")}\"" }
        return "{$entries}"
    }

    private fun jsonArray(key: String, items: List<String>): String =
        "{\"$key\":[${items.joinToString(",") { "\"${it.replace("\"", "'")}\"" }}]}"

    val isRunning: Boolean get() = running.get()
    val port: Int get() = serverSocket?.localPort ?: -1
}
