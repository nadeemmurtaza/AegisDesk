package com.newax.aegis.engine

class TrieNode {
    val children = mutableMapOf<Char, TrieNode>()
    /** Only leaf nodes store refIds to avoid O(W*N) memory where W=word length, N=refCount. */
    val leafRefIds = mutableSetOf<String>()
    /** Accumulated set of all refIds reachable from this prefix — rebuilt on insert/remove. */
    val prefixRefIds = mutableSetOf<String>()
}

object TypeaheadTrie {
    private val root = TrieNode()
    private const val MAX_WORD_LEN = 40

    /** Inserts a word (truncated to MAX_WORD_LEN) and tags the leaf with refId. */
    fun insert(word: String, refId: String) {
        val cleanWord = word.lowercase().replace(Regex("[^a-z0-9]"), "").take(MAX_WORD_LEN)
        if (cleanWord.isEmpty()) return

        var current = root
        for (char in cleanWord) {
            current = current.children.computeIfAbsent(char) { TrieNode() }
            current.prefixRefIds.add(refId)
        }
        current.leafRefIds.add(refId)
    }

    /** Removes a specific refId association from a word. Cleans up empty nodes. */
    fun remove(word: String, refId: String) {
        val cleanWord = word.lowercase().replace(Regex("[^a-z0-9]"), "").take(MAX_WORD_LEN)
        if (cleanWord.isEmpty()) return
        removePath(root, cleanWord, 0, refId)
    }

    private fun removePath(node: TrieNode, word: String, depth: Int, refId: String): Boolean {
        if (depth == word.length) {
            node.leafRefIds.remove(refId)
            node.prefixRefIds.remove(refId)
            return node.children.isEmpty() && node.leafRefIds.isEmpty()
        }
        val child = node.children[word[depth]] ?: return false
        val shouldDeleteChild = removePath(child, word, depth + 1, refId)
        node.prefixRefIds.remove(refId)
        if (shouldDeleteChild) node.children.remove(word[depth])
        return node.children.isEmpty() && node.leafRefIds.isEmpty() && node.prefixRefIds.isEmpty()
    }

    /** O(L) prefix search — returns all refIds reachable from this prefix. */
    fun searchPrefix(prefix: String): Set<String> {
        val cleanPrefix = prefix.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (cleanPrefix.isEmpty()) return emptySet()

        var current = root
        for (char in cleanPrefix) {
            current = current.children[char] ?: return emptySet()
        }
        return current.prefixRefIds.toSet()
    }

    fun clear() {
        root.children.clear()
        root.leafRefIds.clear()
        root.prefixRefIds.clear()
    }
}
