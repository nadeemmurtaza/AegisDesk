package com.newax.aegis.engine

import com.newax.aegis.db.AegisDatabase

object MemoryIndexer {

    fun reindexAll() {
        TypeaheadTrie.clear()

        // Index Knowledge Graph Nodes
        val nodes = KnowledgeGraph.getAllNodes()
        for (node in nodes) {
            val id = "NODE:${node.id}"
            // Index the name
            val words = node.id.split("\\s+".toRegex())
            for (word in words) TypeaheadTrie.insert(word, id)
            
            // Index properties
            for ((_, v) in node.properties) {
                for (word in v.split("\\s+".toRegex())) {
                    TypeaheadTrie.insert(word, id)
                }
            }
        }

        // Index Communication Logs
        val logs = CommunicationLog.getAllLogs()
        for (log in logs) {
            val id = "LOG:${log.timestamp}"
            val words = log.contact.split("\\s+".toRegex()) + log.summary.split("\\s+".toRegex())
            for (word in words) TypeaheadTrie.insert(word, id)
        }
        
        // Index Projects
        val projects = ProjectTracker.getAllProjects()
        for (p in projects) {
            val id = "PROJECT:${p.id}"
            val words = p.id.split("\\s+".toRegex()) + p.status.split("\\s+".toRegex())
            for (word in words) TypeaheadTrie.insert(word, id)
        }

        // Index normalized graph entities by canonical name
        val db = try { AegisDatabase.get } catch (_: IllegalStateException) { null }
        db?.graphDao()?.allEntities(10_000)?.forEach { entity ->
            val entityRef = "ENTITY:${entity.id}"
            entity.canonicalName.split("\\s+".toRegex())
                .filter { it.length >= 2 }
                .forEach { word -> TypeaheadTrie.insert(word, entityRef) }
        }
        // Legacy triple index for backward compat with old data
        db?.tripleDao()?.getAll()?.forEach { t ->
            val tripleId = "TRIPLE:${t.id}"
            (t.subject + " " + t.objectValue).split("\\s+".toRegex())
                .filter { it.length >= 2 }
                .forEach { word -> TypeaheadTrie.insert(word, tripleId) }
        }
    }
}
