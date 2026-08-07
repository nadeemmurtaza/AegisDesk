package com.newax.aegis.engine

import org.json.JSONArray
import org.json.JSONObject

data class Project(
    val id: String,
    var status: String,
    var notes: String,
    var updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

object ProjectTracker {
    private val lock     = Any()
    private val projects = mutableMapOf<String, Project>()

    fun updateProject(id: String, status: String, notes: String) = synchronized(lock) {
        val existing = projects[id]
        projects[id] = Project(
            id = id,
            status = status,
            notes = notes,
            updatedAt = System.currentTimeMillis(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
    }

    fun deleteProject(id: String): Boolean = synchronized(lock) {
        projects.remove(id) != null
    }

    fun getProject(id: String): Project? = synchronized(lock) { projects[id] }

    fun getAllProjects(): List<Project> = synchronized(lock) {
        projects.values.sortedByDescending { it.updatedAt }
    }

    /** Returns projects updated within the last [withinMs] milliseconds. */
    fun getRecentProjects(withinMs: Long = 7 * 24 * 60 * 60 * 1000L): List<Project> = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - withinMs
        projects.values.filter { it.updatedAt >= cutoff }.sortedByDescending { it.updatedAt }
    }

    fun serialize(): String = synchronized(lock) {
        val array = JSONArray()
        for ((_, p) in projects) {
            array.put(JSONObject().apply {
                put("id", p.id)
                put("status", p.status)
                put("notes", p.notes)
                put("updatedAt", p.updatedAt)
                put("createdAt", p.createdAt)
            })
        }
        array.toString()
    }

    fun load(jsonStr: String) = synchronized(lock) {
        projects.clear()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id  = obj.getString("id")
                projects[id] = Project(
                    id = id,
                    status = obj.getString("status"),
                    notes = obj.getString("notes"),
                    updatedAt = if (obj.has("updatedAt")) obj.getLong("updatedAt") else System.currentTimeMillis(),
                    createdAt = if (obj.has("createdAt")) obj.getLong("createdAt") else System.currentTimeMillis()
                )
            }
        } catch (_: Exception) {}
    }
}
