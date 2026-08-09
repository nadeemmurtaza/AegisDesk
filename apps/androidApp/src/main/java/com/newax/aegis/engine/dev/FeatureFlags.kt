package com.newax.aegis.engine.dev

import android.content.Context
import androidx.core.content.edit

object FeatureFlags {

    private const val PREFS_NAME = "aegis_feature_flags"

    enum class Flag(val defaultValue: Boolean, val description: String) {
        OPPORTUNISTIC_INDEXING(true, "Background file indexing during idle"),
        VISUAL_HASHING(true, "Perceptual hash for image dedup"),
        TEXT_EXTRACTION(true, "OCR/text extraction from files"),
        ENTITY_EXTRACTION(true, "NLP entity extraction from text"),
        VECTOR_SEARCH(true, "Semantic vector search"),
        GRAPH_STORE(true, "Normalized graph store (entities/edges)"),
        LLM_FACT_EXTRACTION(true, "LLM-based fact extraction"),
        LLM_TRIPLE_EXTRACTION(true, "LLM-based triple extraction from messages"),
        TRIGGER_ENGINE(true, "Automatic trigger rules engine"),
        HABIT_LEARNING(true, "App usage habit detection"),
        MEMORY_CONSOLIDATION(true, "Duplicate/contradiction detection in memory"),
        SNAPSHOT_COMPILATION(false, "Periodic entity snapshot compilation"),
        PROCEDURE_OPTIMIZER(false, "Auto-optimize learned procedures"),
        GOAL_PLANNER(false, "AI goal decomposition and tracking"),
        FAILURE_LEARNING(true, "Record and learn from execution failures"),
        CONFIDENCE_DECAY(false, "Decay confidence of stale memories"),
        FORGETTING_ENGINE(false, "Remove unused old memories"),
        MULTI_MODEL_ROUTING(false, "Route queries to best available model"),
        FEDERATED_MEMORY_SYNC(false, "Cross-device memory sync"),
        DEVELOPER_CONSOLE(true, "Shake-to-open dev console"),
        DETAILED_METRICS(false, "Collect per-module latency metrics"),
        BENCHMARK_MODE(false, "Run indexing benchmarks"),
        REGRESSION_CHECKS(false, "Auto-verify search consistency after updates"),
        CONTEXT_BUILDER_V2(false, "New context window builder with memory ranking"),
        REASONING_PLANNER(false, "Multi-step chain-of-thought planning"),
    }

    private var prefs: android.content.SharedPreferences? = null
    private val overrides = mutableMapOf<Flag, Boolean>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(flag: Flag): Boolean {
        overrides[flag]?.let { return it }
        return prefs?.getBoolean(flag.name, flag.defaultValue) ?: flag.defaultValue
    }

    fun setEnabled(flag: Flag, enabled: Boolean) {
        prefs?.edit { putBoolean(flag.name, enabled) }
    }

    fun setOverride(flag: Flag, enabled: Boolean) {
        overrides[flag] = enabled
    }

    fun clearOverride(flag: Flag) {
        overrides.remove(flag)
    }

    fun clearAllOverrides() = overrides.clear()

    fun reset(flag: Flag) {
        prefs?.edit { remove(flag.name) }
        overrides.remove(flag)
    }

    fun resetAll() {
        prefs?.edit { clear() }
        overrides.clear()
    }

    fun allFlags(): List<FlagStatus> = Flag.entries.map { flag ->
        FlagStatus(
            flag = flag,
            enabled = isEnabled(flag),
            isOverridden = flag in overrides,
            isDefault = prefs?.contains(flag.name) == false && flag !in overrides
        )
    }

    data class FlagStatus(
        val flag: Flag,
        val enabled: Boolean,
        val isOverridden: Boolean,
        val isDefault: Boolean
    )
}
