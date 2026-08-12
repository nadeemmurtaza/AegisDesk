package com.newax.aegis.engine.grounding

import android.view.accessibility.AccessibilityNodeInfo

object ScreenGrounder {

    data class GroundingResult(
        val node: AccessibilityNodeInfo,
        val confidence: Float,
        val matchedText: String
    )

    private val BUTTON_WORDS  = setOf("button","btn","send","submit","ok","confirm","done","save","post","share","attach","add","open","close","dismiss","back","next","continue","apply")
    private val INPUT_WORDS   = setOf("input","field","text","enter","type","write","message","search","query","box","area")

    fun findTarget(root: AccessibilityNodeInfo, target: String): GroundingResult? {
        val query     = target.trim().lowercase()
        val queryWords = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wantsClick = queryWords.any { it in BUTTON_WORDS }
        val wantsInput = queryWords.any { it in INPUT_WORDS }
        var best: Pair<AccessibilityNodeInfo, Float>? = null

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val score = scoreNode(node, query, queryWords, wantsClick, wantsInput)
            if (score > 0.35f) {
                if (best == null || score > best!!.second) best = Pair(node, score)
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)

        val (node, conf) = best ?: return null
        val matched = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        return GroundingResult(node, conf, matched)
    }

    private fun scoreNode(
        node: AccessibilityNodeInfo,
        query: String,
        queryWords: List<String>,
        wantsClick: Boolean,
        wantsInput: Boolean
    ): Float {
        if (!node.isVisibleToUser) return 0f

        var score = 0f
        val texts = listOfNotNull(
            node.text?.toString()?.lowercase(),
            node.contentDescription?.toString()?.lowercase(),
            node.hintText?.toString()?.lowercase(),
            node.viewIdResourceName?.substringAfterLast('/')?.replace('_',' ')?.lowercase()
        )

        for (t in texts) {
            val s = when {
                t == query                          -> 1.0f
                t.contains(query)                   -> 0.80f
                query.contains(t) && t.length > 2  -> 0.65f
                else -> {
                    val tw = t.split(Regex("\\s+"))
                    val common = queryWords.count { qw -> tw.any { it.contains(qw) || qw.contains(it) } }
                    if (queryWords.isEmpty()) 0f else common.toFloat() / queryWords.size * 0.55f
                }
            }
            if (s > score) score = s
        }

        if (score < 0.1f) return 0f

        // Bonus: node interactability matches intent
        if (wantsClick && node.isClickable) score = minOf(1f, score + 0.15f)
        if (wantsInput && node.isEditable)  score = minOf(1f, score + 0.15f)

        // Penalty: disabled or non-interactive when we need interaction
        if (!node.isEnabled) score *= 0.3f

        return score
    }

    // Scroll down looking for a target across multiple swipes
    fun findWithScroll(
        getRoot: () -> AccessibilityNodeInfo?,
        target: String,
        maxScrolls: Int = 5
    ): GroundingResult? {
        repeat(maxScrolls) {
            val root = getRoot() ?: return null
            findTarget(root, target)?.let { return it }
            // caller is responsible for performing the scroll between calls
        }
        return null
    }
}
