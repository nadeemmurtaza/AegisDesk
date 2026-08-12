package com.newax.aegis.engine.resource

enum class ResourceClass {
    TINY,     // concurrency 4 — cheap reads, stats, cache lookups
    LIGHT,    // concurrency 2 — FTS, graph queries, small writes
    HEAVY,    // concurrency 1 — model inference, file indexing, embedding
    CRITICAL  // exclusive — preempts HEAVY, blocks background LIGHT
}

enum class JobPriority(val level: Int) {
    P0_USER_VISIBLE(0),
    P1_ACTIVE_SEARCH(1),
    P2_MEMORY_WRITE(2),
    P3_INDEXING(3),
    P4_EMBEDDINGS(4),
    P5_GRAPH_OPT(5),
    P6_COMPACTION(6)
}

sealed class JobResult<out T> {
    data class Success<T>(val value: T) : JobResult<T>()
    data class Failure(val error: Throwable) : JobResult<Nothing>()
    object Cancelled : JobResult<Nothing>()
    object TimedOut : JobResult<Nothing>()
    object ResourceDenied : JobResult<Nothing>()
}

data class AegisJob(
    val id: String,
    val label: String,
    val resourceClass: ResourceClass,
    val priority: JobPriority,
    val cpuBudget: Int = 2,
    val ramBudgetMb: Int = 32,
    val cancellable: Boolean = true,
    val checkpointable: Boolean = false,
    val deadlineMs: Long = Long.MAX_VALUE,
    val block: suspend () -> Unit
)
