package com.newax.aegis.engine.context

import java.util.concurrent.CopyOnWriteArrayList

data class ActiveContext(
    val currentPackage: String = "",
    val currentScreenSignature: String = "",
    val activePersonEntityId: Long? = null,
    val activePersonName: String? = null,
    val activeProjectId: String? = null,
    val activeFileId: Long? = null,
    val activeFileName: String? = null,
    val activeTaskDescription: String? = null,
    val activeConversationId: String? = null,
    val recentEntityIds: List<Long> = emptyList(),
    val locationCategory: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

object ContextEngine {

    @Volatile private var current = ActiveContext()
    private val listeners = CopyOnWriteArrayList<(ActiveContext) -> Unit>()

    fun get(): ActiveContext = current

    fun updatePackage(pkg: String) = update { it.copy(currentPackage = pkg) }
    fun updateScreen(sig: String) = update { it.copy(currentScreenSignature = sig) }
    fun updatePerson(entityId: Long?, name: String?) = update { it.copy(activePersonEntityId = entityId, activePersonName = name) }
    fun updateProject(projectId: String?) = update { it.copy(activeProjectId = projectId) }
    fun updateFile(fileId: Long?, fileName: String?) = update { it.copy(activeFileId = fileId, activeFileName = fileName) }
    fun updateTask(description: String?) = update { it.copy(activeTaskDescription = description) }
    fun updateConversation(id: String?) = update { it.copy(activeConversationId = id) }
    fun updateLocation(category: String) = update { it.copy(locationCategory = category) }

    fun pushRecentEntity(entityId: Long) = update {
        val updated = (listOf(entityId) + it.recentEntityIds).distinct().take(10)
        it.copy(recentEntityIds = updated)
    }

    fun resolveRef(ref: String): String? {
        val ctx = current
        return when (ref.lowercase().trim()) {
            "him", "her", "them", "he", "she", "they" -> ctx.activePersonName
            "it", "that", "this" -> ctx.activeFileName ?: ctx.activeProjectId
            "there", "here" -> ctx.locationCategory.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    fun addListener(listener: (ActiveContext) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (ActiveContext) -> Unit) { listeners.remove(listener) }

    private fun update(transform: (ActiveContext) -> ActiveContext) {
        current = transform(current).copy(timestampMs = System.currentTimeMillis())
        listeners.forEach { it(current) }
    }
}
