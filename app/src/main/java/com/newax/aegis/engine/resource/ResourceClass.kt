package com.newax.aegis.engine.resource

enum class ResourceClass { TINY, LIGHT, HEAVY, CRITICAL }

enum class JobPriority(val level: Int) {
    P0_USER_VISIBLE(0),
    P1_ACTIVE_SEARCH(1),
    P2_MEMORY_WRITE(2),
    P3_INDEXING(3),
    P4_EMBEDDINGS(4),
    P5_GRAPH_OPT(5),
    P6_COMPACTION(6)
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
    val block: suspend () -> Unit
)
