package com.newax.aegis.engine

import org.json.JSONArray
import org.json.JSONObject

data class GraphEdge(val from: String, val relation: String, val to: String)
data class GraphNode(val id: String, val properties: MutableMap<String, String> = mutableMapOf())

object KnowledgeGraph {
    private val lock  = Any()
    private val edges = mutableListOf<GraphEdge>()
    private val nodes = mutableMapOf<String, GraphNode>()

    fun addEdge(from: String, relation: String, to: String) = synchronized(lock) {
        val edge = GraphEdge(from, relation, to)
        if (edges.none { it.from == edge.from && it.relation == edge.relation && it.to == edge.to }) {
            edges.add(edge)
        }
        if (!nodes.containsKey(from)) nodes[from] = GraphNode(from)
        if (!nodes.containsKey(to))   nodes[to]   = GraphNode(to)
    }

    fun updateNodeProperty(id: String, key: String, value: String) = synchronized(lock) {
        if (!nodes.containsKey(id)) nodes[id] = GraphNode(id)
        nodes[id]?.properties?.put(key, value)
    }

    fun deleteEdge(from: String, relation: String, to: String) = synchronized(lock) {
        edges.removeAll { it.from.equals(from, true) && it.relation.equals(relation, true) && it.to.equals(to, true) }
    }

    fun deleteNode(id: String) = synchronized(lock) {
        nodes.remove(id)
        edges.removeAll { it.from.equals(id, true) || it.to.equals(id, true) }
    }

    fun query(entity: String): List<GraphEdge> = synchronized(lock) {
        edges.filter { it.from.equals(entity, true) || it.to.equals(entity, true) }.toList()
    }

    /** BFS path between two nodes; returns null if unreachable or depth > maxDepth. */
    fun findPath(from: String, to: String, maxDepth: Int = 5): List<GraphEdge>? = synchronized(lock) {
        if (from.equals(to, true)) return emptyList()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, List<GraphEdge>>>()
        queue.add(from to emptyList())
        while (queue.isNotEmpty()) {
            val (current, path) = queue.removeFirst()
            if (path.size >= maxDepth) continue
            val outgoing = edges.filter { it.from.equals(current, true) }
            for (edge in outgoing) {
                if (edge.to.equals(to, true)) return path + edge
                if (visited.add(edge.to)) queue.add(edge.to to path + edge)
            }
        }
        null
    }

    fun getNodeInfo(id: String): String = synchronized(lock) {
        val node = nodes[id] ?: return "Node not found."
        if (node.properties.isEmpty()) return "Node '$id' has no properties."
        node.properties.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }

    fun getAllNodes(): List<GraphNode> = synchronized(lock) { nodes.values.toList() }

    fun serialize(): String = synchronized(lock) {
        val obj        = JSONObject()
        val edgesArray = JSONArray()
        for (edge in edges) {
            edgesArray.put(JSONObject().apply {
                put("from", edge.from); put("relation", edge.relation); put("to", edge.to)
            })
        }
        obj.put("edges", edgesArray)
        val nodesObj = JSONObject()
        for ((id, node) in nodes) {
            val propsObj = JSONObject()
            for ((k, v) in node.properties) propsObj.put(k, v)
            nodesObj.put(id, propsObj)
        }
        obj.put("nodes", nodesObj)
        obj.toString()
    }

    fun load(jsonStr: String) = synchronized(lock) {
        edges.clear(); nodes.clear()
        try {
            val obj        = JSONObject(jsonStr)
            val edgesArray = obj.getJSONArray("edges")
            for (i in 0 until edgesArray.length()) {
                val e = edgesArray.getJSONObject(i)
                edges.add(GraphEdge(e.getString("from"), e.getString("relation"), e.getString("to")))
            }
            val nodesObj = obj.getJSONObject("nodes")
            for (key in nodesObj.keys()) {
                val propsObj = nodesObj.getJSONObject(key)
                val node = GraphNode(key)
                for (propKey in propsObj.keys()) node.properties[propKey] = propsObj.getString(propKey)
                nodes[key] = node
            }
        } catch (_: Exception) {}
    }
}
