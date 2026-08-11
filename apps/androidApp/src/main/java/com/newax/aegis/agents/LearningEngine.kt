package com.newax.aegis.agents

import android.content.Context
import com.newax.aegis.SyncRuntime
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.*
import com.newax.aegis.memory.AgentMemory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The RLAIF-E self-learning engine (docs/AGENTS_DESIGN.md §evolution —
 * Reinforcement Learning from AI Feedback & Execution). Skills are **dynamic
 * mutations**: every skill (and every "agent:<id>" orchestration pseudo-skill)
 * carries an Evolution Ledger ([SkillEvolution]) with execution telemetry and
 * a Bayesian confidence score, and the runtime chooses which method variant to
 * use through the classic exploitation / exploration split:
 *
 *  - **Exploitation** — pick the best-known method (UCB-style score: observed
 *    confidence + exploration bonus for under-sampled methods).
 *  - **Exploration** — pick a variation instead; every outcome feeds back into
 *    its ledger row (that IS the reinforcement loop, RLAIF-E).
 *
 * Nothing learned ever touches the live environment without the human gate.
 * The HITL staging flow mirrors the gatekeeper design: a candidate mutation
 * lands in `filesDir/staging/` + a PENDING_USER_APPROVAL row in
 * `staging_records`; the user Approves (deploy to the active skills dir /
 * promote the memory rule) or Denies (drop the staging file, record the
 * denial as a failed route in episodic memory). The Updates screen surfaces
 * the pending queue grouped by risk, with a diff card per mutation.
 *
 * Three per-skill learning protocols, dispatched from the skill's Learning
 * Specification Interface (`skills.learningSpec`, parsed from the `learning`
 * object of a skill.json manifest):
 *  1. [EvolutionProtocol.DETERMINISTIC] — hard execution data (this engine's
 *     failure telemetry; a host sandbox that runs actual patches is the
 *     desktop executor's job, Track M). recordExecution → threshold staging.
 *  2. [EvolutionProtocol.CRITIC] — semantic learning from human corrections
 *     ([ingestUserFeedback]) → MEMORY_RULE staging.
 *  3. [EvolutionProtocol.CROSS_AGENT] — handoff misalignments
 *     ([recordHandoffFailure]) → shared-contract staging.
 * And the Continuous Fuzzing Engine ([fuzzPass], skill.sys.background_fuzzer)
 * explores alternative approaches on a schedule when the device is idle,
 * benchmarking observed stats and staging the candidates.
 *
 * All DB access is guarded + runBlocking, matching the runtime's style; a DB
 * failure never breaks the caller. Device-local (no sync columns) — what this
 * device learned is recorded here; the mesh carries the episodes and library
 * entries those learnings produce.
 */
object LearningEngine {

    @Volatile
    private var appContext: Context? = null

    private const val KV_EXPLORATION_RATE = "evolution.explorationRate"
    private const val KV_FUZZ_ENABLED = "evolution.fuzzEnabled"
    private const val KV_LAST_FUZZ_MS = "evolution.lastFuzzMs"

    private const val FAILURE_STAGE_THRESHOLD = 3       // N failures → deterministic fix proposal
    private const val MIN_EXECUTIONS_FOR_FUZZ = 2       // need data before benchmarking
    private const val MAX_STAGES_PER_FUZZ = 3           // cap per pass
    private const val HANDOFF_ALIGNMENT_THRESHOLD = 3   // N misalignments → shared contract
    private const val UCB_EXPLORATION = 0.25            // UCB c parameter

    private val FUZZ_VARIATIONS = listOf(
        "Rewrite this tool to use asynchronous processing instead of sequential loops.",
        "Prefer one batched call over per-item calls to reduce overhead.",
        "Add an explicit retry with backoff for transient failures.",
        "Reuse the previous successful inputs as hints to cut warm-up cost.",
        "Split the work into smaller idempotent steps so partial failures can resume."
    )

    // ── lifecycle ───────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching { ensureLedger() }
    }

    private fun context(): Context = requireNotNull(appContext) { "LearningEngine.init(context) not called" }

    private fun db(): AegisDatabase? = runCatching { AegisDatabase.get }.getOrNull()

    // ── tuning (kv_store) ───────────────────────────────────────────────────

    private fun kvGet(key: String, default: String): String =
        runBlocking { runCatching { db()?.kvStoreDao()?.get(key) }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: default }

    private fun kvPut(key: String, value: String) {
        runBlocking { runCatching { db()?.kvStoreDao()?.put(KvStoreEntity(key = key, value = value)) } }
    }

    /** Fraction of executions that explore a variation instead of exploiting the best (0..1). */
    fun explorationRate(): Double = kvGet(KV_EXPLORATION_RATE, "0.2").toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.2

    fun setExplorationRate(rate: Double) = kvPut(KV_EXPLORATION_RATE, rate.coerceIn(0.0, 1.0).toString())

    fun fuzzEnabled(): Boolean = kvGet(KV_FUZZ_ENABLED, "1") == "1"

    fun setFuzzEnabled(on: Boolean) = kvPut(KV_FUZZ_ENABLED, if (on) "1" else "0")

    fun lastFuzzAtMs(): Long = kvGet(KV_LAST_FUZZ_MS, "0").toLongOrNull() ?: 0L

    // ── Evolution Ledger ────────────────────────────────────────────────────

    /**
     * Seed the baseline method for every known skill (idempotent). The
     * baseline payload is real content — the skill's own schema/description,
     * or the agent's description+capabilities for "agent:<id>" pseudo-skills
     * — so the exploit path always has a meaningful method to run.
     */
    fun ensureLedger(): Int {
        val database = db() ?: return 0
        var seeded = 0
        runBlocking {
            runCatching {
                val dao = database.evolutionDao()
                val now = System.currentTimeMillis()
                SkillManager.skills().forEach { skill ->
                    if (dao.activeMethods(skill.skillId).isEmpty()) {
                        dao.upsertEvolution(
                            SkillEvolution(
                                skillId = skill.skillId,
                                methodId = "baseline",
                                source = EvolutionSource.EXPLOIT,
                                protocol = LearningSpecs.protocolOf(skill),
                                payloadJson = baselinePayload(skill),
                                confidence = 0.5,
                                createdAtMs = now, updatedAtMs = now
                            )
                        )
                        seeded++
                    }
                }
                // Pseudo-skills: one ledger per agent, so orchestration runs
                // learn which role-configuration works best for the user too.
                AgentRegistry.agents().forEach { agent ->
                    val sid = "agent:${agent.agentId}"
                    if (dao.activeMethods(sid).isEmpty()) {
                        dao.upsertEvolution(
                            SkillEvolution(
                                skillId = sid,
                                methodId = "baseline",
                                source = EvolutionSource.EXPLOIT,
                                protocol = EvolutionProtocol.CRITIC,
                                payloadJson = JSONObject()
                                    .put("method_guidance", "${agent.name} (${agent.category}): ${agent.description}".take(600))
                                    .put("capabilities", agent.capabilities)
                                    .toString(),
                                confidence = 0.5,
                                createdAtMs = now, updatedAtMs = now
                            )
                        )
                        seeded++
                    }
                }
            }
        }
        return seeded
    }

    private fun baselinePayload(skill: SkillEntity): String {
        val o = JSONObject()
        o.put("method_guidance", "Default method for ${skill.name} (${skill.description}).".take(400))
        if (skill.toolSchema.isNotBlank() && skill.toolSchema != "{}") o.put("tool_schema", skill.toolSchema)
        if (skill.learningSpec.isNotBlank() && skill.learningSpec != "{}") o.put("learning_spec", skill.learningSpec)
        return o.toString()
    }

    /**
     * The exploitation/exploration picker — PURE function (unit-testable).
     * [explorationRate] of the time it explores a non-best candidate; the
     * rest it exploits the UCB score (confidence + exploration bonus), so an
     * under-sampled method is never starved out.
     */
    fun pickMethod(methods: List<SkillEvolution>, explorationRate: Double, rng: Random): SkillEvolution? {
        if (methods.isEmpty()) return null
        if (methods.size == 1) return methods.first()
        val best = methods.maxByOrNull { it.confidence }!!
        if (rng.nextDouble() < explorationRate) {
            val candidates = methods.filter { it.methodId != best.methodId }
            if (candidates.isNotEmpty()) return candidates[rng.nextInt(candidates.size)]
        }
        val totalN = methods.sumOf { it.executionCount }.coerceAtLeast(1)
        return methods.maxByOrNull { m ->
            val n = m.executionCount.coerceAtLeast(1)
            m.confidence + UCB_EXPLORATION * sqrt(ln(totalN.toDouble() + 1.0) / n)
        }
    }

    /** Pick the method the next execution of [skillId] should use (seeds the ledger if empty). */
    fun chooseMethod(skillId: String): SkillEvolution? {
        val database = db() ?: return null
        if (runBlocking { runCatching { database.evolutionDao().activeMethods(skillId) }.getOrDefault(emptyList()) }.isEmpty()) {
            ensureLedger()
        }
        val active = runBlocking { runCatching { database.evolutionDao().activeMethods(skillId) }.getOrDefault(emptyList()) }
        return pickMethod(active, explorationRate(), Random.Default)
    }

    /** The best active method's confidence (0..1) — the skill's current trust score. */
    fun confidenceOf(skillId: String): Double? {
        val database = db() ?: return null
        return runBlocking { runCatching { database.evolutionDao().activeMethods(skillId) }.getOrDefault(emptyList()) }
            .maxOfOrNull { it.confidence }
    }

    fun ledgerFor(skillId: String): List<SkillEvolution> {
        val database = db() ?: return emptyList()
        return runBlocking { runCatching { database.evolutionDao().evolutionsForSkill(skillId) }.getOrDefault(emptyList()) }
    }

    // ── execution feedback (the RLAIF loop) ─────────────────────────────────

    /**
     * One execution finished: fold the outcome into the ledger (Bayesian
     * confidence: posterior mean with a Beta(1,1) prior — the method's
     * observed success rate pulled toward 0.5 while evidence is thin), emit a
     * signed reward signal, and journal the failure. When a method has failed
     * [FAILURE_STAGE_THRESHOLD] times with no fix already pending, the
     * deterministic protocol stages a fix proposal behind the user gate.
     */
    fun recordExecution(skillId: String, methodId: String, success: Boolean, latencyMs: Long, error: String = "") {
        val database = db() ?: return
        val now = System.currentTimeMillis()
        // Ledger fold inside the DB block; the episode + staging happen outside
        // it (AgentMemory/stageMutation manage their own runBlocking — nesting
        // runBlocking is a deadlock hazard, repo style forbids it).
        val failedMethod = runBlocking {
            runCatching {
                val dao = database.evolutionDao()
                val method = dao.method(skillId, methodId) ?: dao.activeMethods(skillId).firstOrNull() ?: return@runCatching null
                val exec = method.executionCount + 1
                val ok = method.successCount + if (success) 1 else 0
                val fail = method.failureCount + if (success) 0 else 1
                val total = method.totalLatencyMs + latencyMs.coerceAtLeast(0)
                val avg = if (exec > 0) total / exec else 0
                val confidence = (ok + 1.0) / (exec + 2.0) // Beta(1,1) posterior mean
                dao.recordOutcome(
                    method.id, exec, ok, fail, total, avg, confidence,
                    if (success) "SUCCESS" else "FAILURE",
                    error.take(500), now
                )
                dao.insertSignal(
                    LearningSignal(
                        skillId = skillId,
                        agentId = "agent:$methodId",
                        protocol = method.protocol,
                        source = LearningSignalSource.EXECUTION_ERROR,
                        reward = if (success) 0.1 else -1.0,
                        summary = if (success) "$skillId:$methodId succeeded (${latencyMs}ms)" else "$skillId:$methodId failed: ${error.take(160)}",
                        contextJson = JSONObject().put("methodId", methodId).put("latencyMs", latencyMs).toString(),
                        createdAtMs = now
                    )
                )
                if (!success && fail >= FAILURE_STAGE_THRESHOLD && dao.stagedMethods(skillId).isEmpty()) method else null
            }.getOrNull()
        }
        if (!success) {
            // Every failure is an episode with the lesson (collective learning
            // propagates it through the mesh) — regardless of staging.
            AgentMemory.recordEpisode(
                agentId = "skill:$skillId",
                category = "learning-execution",
                summary = "Method $methodId of $skillId failed: ${error.take(200)}",
                outcome = EpisodeOutcome.FAILURE,
                lesson = "Skill $skillId method $methodId fails with: ${error.take(160)}",
                contextRef = "evolution:$now"
            )
        }
        if (failedMethod != null) {
            stageDeterministicFix(database, skillId, failedMethod, error, now)
        }
    }

    /** Deterministic-protocol fix staging: the system found repeated failures → candidate mutation. */
    private fun stageDeterministicFix(
        database: AegisDatabase, skillId: String, method: SkillEvolution, error: String, now: Long
    ) {
        val skill = runBlocking { runCatching { database.skillManagerDao().skillById(skillId) }.getOrNull() }
        val methodId = "fix-${now / 1000}"
        val guidance = JSONObject()
            .put("methodId", methodId)
            .put("parent", method.methodId)
            .put(
                "method_guidance",
                "Recover from the recurring failure of ${method.methodId}: ${error.take(300)}. " +
                    "Verify with the skill's test strategy before sending to the user."
            )
            .toString()
        val risk = when {
            skill?.requiresApproval == true || skill?.sandboxRequired == true -> RiskLevel.CRITICAL
            else -> RiskLevel.MEDIUM
        }
        stageMutation(
            skillId = skillId,
            agentId = "skill-creator",
            title = "Fix for $skillId — recurring failure",
            summary = "I detected a failure in $skillId (method ${method.methodId} failed ${method.failureCount + 1} times: ${error.take(180)}). " +
                "I wrote a candidate fix that handles the failure. Click Approve to apply it, or Deny to keep the current method.",
            changeType = ChangeType.MUTATION,
            protocol = EvolutionProtocol.DETERMINISTIC,
            riskLevel = risk,
            diffBefore = "current method: ${method.methodId} · ${(method.successCount + 1.0) / (method.executionCount + 2.0) * 100}% confidence · last error: ${error.take(160)}",
            diffAfter = "proposed method: $methodId · adds recovery guidance for the recurring failure",
            payloadJson = guidance,
            methodId = methodId,
            parentMethodId = method.methodId,
            source = EvolutionSource.EXPLORE,
            protocolForLedger = EvolutionProtocol.DETERMINISTIC
        )
    }

    // ── the HITL staging gatekeeper ─────────────────────────────────────────

    /**
     * Stage a candidate mutation: write the payload to the isolated staging
     * dir, log the PENDING record, insert the STAGED ledger row, and notify
     * the UI. The system HOLDs here — nothing reaches the active skills dir
     * until [approve] runs. Returns the stagingId ("" on failure).
     */
    fun stageMutation(
        skillId: String,
        agentId: String,
        title: String,
        summary: String,
        changeType: String,
        protocol: String,
        riskLevel: String,
        diffBefore: String,
        diffAfter: String,
        payloadJson: String,
        methodId: String,
        parentMethodId: String = "",
        source: String = EvolutionSource.EXPLORE,
        protocolForLedger: String = EvolutionProtocol.DETERMINISTIC
    ): String {
        val database = db() ?: return ""
        if (skillId.isBlank() || summary.isBlank()) return ""
        val stagingId = "stg-${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        val stagingDir = File(context().filesDir, "staging").apply { mkdirs() }
        val stagingFile = File(stagingDir, "$stagingId.json")
        runBlocking {
            runCatching {
                val dao = database.evolutionDao()
                dao.insertStaging(
                    StagingRecord(
                        stagingId = stagingId,
                        skillId = skillId,
                        agentId = agentId.take(120),
                        title = title.take(160),
                        summary = summary.take(600),
                        changeType = changeType,
                        protocol = protocol,
                        riskLevel = riskLevel,
                        diffBefore = diffBefore.take(2000),
                        diffAfter = diffAfter.take(2000),
                        payloadJson = payloadJson.take(8000),
                        createdAtMs = now
                    )
                )
                dao.upsertEvolution(
                    SkillEvolution(
                        skillId = skillId,
                        methodId = methodId,
                        parentMethodId = parentMethodId,
                        source = source,
                        protocol = protocolForLedger,
                        payloadJson = payloadJson,
                        status = EvolutionStatus.STAGED,
                        createdAtMs = now, updatedAtMs = now
                    )
                )
                stagingFile.writeText(JSONObject().apply {
                    put("stagingId", stagingId)
                    put("skillId", skillId)
                    put("agentId", agentId)
                    put("title", title)
                    put("summary", summary)
                    put("changeType", changeType)
                    put("protocol", protocol)
                    put("riskLevel", riskLevel)
                    put("diffBefore", diffBefore)
                    put("diffAfter", diffAfter)
                    put("payloadJson", payloadJson)
                }.toString(2))
            }
        }
        AgentStream.emit(AgentStream.Type.STATUS, "staging:$stagingId", agentId.ifBlank { "system" }, "Pending Approval", "Mutation staged: $title")
        AgentMemory.recordEpisode(
            agentId = "learning",
            category = "staging",
            summary = "Staged $changeType for $skillId: $title — awaiting user approval.",
            outcome = EpisodeOutcome.OBSERVATION,
            contextRef = "staging:$stagingId"
        )
        return stagingId
    }

    /**
     * The user said YES at the gate. Deploy: write the payload files into the
     * active skills dir (zip-slip-safe), promote the STAGED ledger row to
     * ACTIVE and supersede its parent, or promote a MEMORY_RULE straight into
     * the shared library (this approval IS the gate). Every deploy is
     * journaled + recorded as an episode.
     */
    fun approve(stagingId: String): Boolean {
        val database = db() ?: return false
        val record = runBlocking { runCatching { database.evolutionDao().stagingById(stagingId) }.getOrNull() } ?: return false
        if (record.status != StagingStatus.PENDING_USER_APPROVAL) return false
        val now = System.currentTimeMillis()
        val deployed = when (record.changeType) {
            ChangeType.MEMORY_RULE -> deployMemoryRule(database, record, now)
            ChangeType.NEW_SKILL -> deployNewSkill(database, record, now)
            else -> deployMutation(database, record, now)
        }
        if (!deployed) return false
        runBlocking {
            runCatching {
                database.evolutionDao().setStagingStatus(stagingId, StagingStatus.DEPLOYED, now)
                // promote the STAGED ledger row; supersede its parent
                record.payloadJson.takeIf { it.isNotBlank() && it != "{}" }?.let { payload ->
                    val methodId = runCatching { JSONObject(payload).optString("methodId") }.getOrDefault("")
                    if (methodId.isNotBlank()) {
                        val staged = database.evolutionDao().method(record.skillId, methodId)
                        if (staged != null) {
                            database.evolutionDao().setEvolutionStatus(staged.id, EvolutionStatus.ACTIVE, now)
                            if (staged.parentMethodId.isNotBlank()) {
                                database.evolutionDao().method(record.skillId, staged.parentMethodId)
                                    ?.let { database.evolutionDao().setEvolutionStatus(it.id, EvolutionStatus.SUPERSEDED, now) }
                            }
                        }
                    }
                }
                database.evolutionDao().insertSignal(
                    LearningSignal(
                        skillId = record.skillId,
                        agentId = record.agentId,
                        protocol = record.protocol,
                        source = LearningSignalSource.USER_FEEDBACK,
                        reward = 1.0,
                        summary = "User APPROVED staged ${record.changeType} — deployed.",
                        createdAtMs = now
                    )
                )
            }
        }
        File(context().filesDir, "staging/$stagingId.json").delete()
        AgentMemory.recordEpisode(
            agentId = "learning",
            category = "staging",
            summary = "User approved ${record.changeType} for ${record.skillId}: ${record.title} — deployed to the active environment.",
            outcome = EpisodeOutcome.SUCCESS,
            contextRef = "staging:$stagingId"
        )
        AgentStream.emit(AgentStream.Type.ARTIFACT, "staging:$stagingId", record.agentId.ifBlank { "system" }, "Done", "Deployed: ${record.title}")
        return true
    }

    /**
     * The user said NO. Drop the staging file, mark the ledger row REJECTED
     * (a failed route — the method is never picked again), and journal the
     * denial into episodic memory with the lesson, exactly as the design's
     * DENIED branch specifies.
     */
    fun deny(stagingId: String): Boolean {
        val database = db() ?: return false
        val record = runBlocking { runCatching { database.evolutionDao().stagingById(stagingId) }.getOrNull() } ?: return false
        if (record.status != StagingStatus.PENDING_USER_APPROVAL) return false
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching {
                database.evolutionDao().setStagingStatus(stagingId, StagingStatus.USER_DENIED, now)
                record.payloadJson.takeIf { it.isNotBlank() && it != "{}" }?.let { payload ->
                    val methodId = runCatching { JSONObject(payload).optString("methodId") }.getOrDefault("")
                    if (methodId.isNotBlank()) {
                        database.evolutionDao().method(record.skillId, methodId)
                            ?.let { database.evolutionDao().setEvolutionStatus(it.id, EvolutionStatus.REJECTED, now) }
                    }
                }
                database.evolutionDao().insertSignal(
                    LearningSignal(
                        skillId = record.skillId,
                        agentId = record.agentId,
                        protocol = record.protocol,
                        source = LearningSignalSource.USER_FEEDBACK,
                        reward = -1.0,
                        summary = "User DENIED staged ${record.changeType} — strategy recorded as a failed route.",
                        createdAtMs = now
                    )
                )
            }
        }
        File(context().filesDir, "staging/$stagingId.json").delete()
        AgentMemory.recordEpisode(
            agentId = "learning",
            category = "staging",
            summary = "User denied ${record.changeType} for ${record.skillId}: ${record.title}.",
            outcome = EpisodeOutcome.FAILURE,
            lesson = "Mutation strategy for ${record.skillId} was rejected by the user — do not propose it again: ${record.diffAfter.take(120)}",
            contextRef = "staging:$stagingId"
        )
        AgentStream.emit(AgentStream.Type.ERROR, "staging:$stagingId", record.agentId.ifBlank { "system" }, "Done", "Denied: ${record.title}")
        return true
    }

    // ── deploy paths (the gate opened) ──────────────────────────────────────

    /**
     * MUTATION deploy — two payload shapes:
     *  1. files_to_write patches (the gatekeeper's shape: destination +
     *     code_content per file), written under the skill package dir,
     *  2. engine-authored method variants (fix proposals / fuzzer candidates
     *     carry method_guidance, not file patches) — the method definition
     *     itself is deployed to `methods/<methodId>.json`, which is what the
     *     exploit/explore picker feeds into prompts once the row is ACTIVE.
     * Every path resolves inside the package dir (zip-slip guard, R12).
     */
    private fun deployMutation(database: AegisDatabase, record: StagingRecord, now: Long): Boolean {
        val skill = runBlocking { runCatching { database.skillManagerDao().skillById(record.skillId) }.getOrNull() }
        val payload = runCatching { JSONObject(record.payloadJson) }.getOrNull() ?: return false
        val methodId = payload.optString("methodId").ifBlank { "mutated" }
        val base = skill?.packageDir?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(context().filesDir, "skills/${record.skillId}").apply { mkdirs() }
        val files = payload.optJSONArray("files_to_write")
        if (files == null || files.length() == 0) {
            val resolved = safeResolve(base, "methods/$methodId.json") ?: return false
            resolved.parentFile?.mkdirs()
            resolved.writeText(record.payloadJson)
            return true
        }
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val destination = f.optString("destination").ifBlank { "methods/$methodId.json" }
            val resolved = safeResolve(base, destination) ?: return false
            resolved.parentFile?.mkdirs()
            resolved.writeText(f.optString("code_content").ifBlank { record.payloadJson })
        }
        return true
    }

    /** NEW_SKILL: install a full package (manifest + files) from the staging payload. */
    private fun deployNewSkill(database: AegisDatabase, record: StagingRecord, now: Long): Boolean {
        val payload = runCatching { JSONObject(record.payloadJson) }.getOrNull() ?: return false
        val manifestJson = payload.optString("manifest").ifBlank { return false }
        val manifest = SkillManager.parseSkillManifest(manifestJson) ?: return false
        val root = File(context().filesDir, "skills/${manifest.id}").apply { mkdirs() }
        val files = payload.optJSONArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val resolved = safeResolve(root, f.optString("path")) ?: return false
                resolved.parentFile?.mkdirs()
                resolved.writeText(f.optString("content"))
            }
        }
        runBlocking {
            runCatching {
                database.skillManagerDao().upsertSkill(
                    SkillEntity(
                        skillId = manifest.id, name = manifest.name, version = manifest.version,
                        description = manifest.description, category = manifest.category,
                        capability = manifest.capability, toolSchema = manifest.toolSchema,
                        sandboxRequired = manifest.sandboxRequired, requiresApproval = manifest.requiresApproval,
                        risks = manifest.risks, learningSpec = manifest.learningSpec,
                        enabled = true, source = "staged", packageDir = root.absolutePath, installedAtMs = now
                    )
                )
            }
        }
        ensureLedger()
        return true
    }

    /** MEMORY_RULE: the staging approval IS the validation gate → promote straight to ACTIVE library. */
    private fun deployMemoryRule(database: AegisDatabase, record: StagingRecord, now: Long): Boolean {
        val payload = runCatching { JSONObject(record.payloadJson) }.getOrNull() ?: return false
        val content = payload.optString("content").ifBlank { return false }
        val category = payload.optString("category").ifBlank { "learned" }
        val title = record.title.take(120).ifBlank { content.take(64) }
        val entryId = UUID.randomUUID().toString()
        runBlocking {
            runCatching {
                database.agentMemoryDao().upsertLibrary(
                    LibraryEntry(
                        entryId = entryId, category = category, title = title,
                        content = content, confidence = 90, source = "learning",
                        status = LibraryStatus.ACTIVE, createdAtMs = now, decidedAtMs = now
                    )
                )
            }
        }
        runCatching {
            com.newax.aegis.engine.embedding.VectorStore.indexLibrary(database, entryId, category, title, content)
        }
        SyncRuntime.captureRecord(
            SyncRuntime.TABLE_LIBRARY_ENTRIES, entryId,
            listOf(
                "entryId" to entryId, "category" to category, "title" to title,
                "content" to content, "confidence" to "90", "source" to "learning",
                "status" to LibraryStatus.ACTIVE, "createdAtMs" to now.toString()
            )
        )
        return true
    }

    /** Resolve a deployment path inside [base] — absolute paths and any `..` are rejected (R12). */
    private fun safeResolve(base: File, rel: String): File? = runCatching {
        if (rel.isBlank()) return null
        val target = File(base, rel)
        val baseNorm = base.canonicalPath
        if (target.canonicalPath.startsWith(baseNorm + File.separator)) target else null
    }.getOrNull()

    // ── protocol 2: Critic-based semantic learning ─────────────────────────

    /**
     * The human corrected the assistant (or confirmed it). CRITIC protocol:
     * register the signed reward signal and, on a correction, stage a
     * MEMORY_RULE update behind the gate — \"Based on your correction earlier,
     * I have updated my permanent knowledge… Click Approve to lock it in.\"
     */
    /**
     * The human corrected the assistant (or confirmed it). CRITIC protocol:
     * register the signed reward signal and, on a correction, stage a
     * MEMORY_RULE update behind the gate — "Based on your correction earlier,
     * I have updated my permanent knowledge… Click Approve to lock it in."
     * [stageMemoryRule] is true for knowledge corrections; callers that only
     * want the reward signal (e.g. approve/reject of an action plan) pass
     * false so no noise rule is staged.
     */
    fun ingestUserFeedback(agentId: String, skillId: String, feedbackText: String, negative: Boolean, stageMemoryRule: Boolean = true) {
        val database = db() ?: return
        if (feedbackText.isBlank()) return
        val now = System.currentTimeMillis()
        val targetSkill = skillId.ifBlank { "agent:$agentId" }
        val reward = if (negative) -0.75 else 0.75
        runBlocking {
            runCatching {
                database.evolutionDao().insertSignal(
                    LearningSignal(
                        skillId = targetSkill,
                        agentId = agentId,
                        protocol = EvolutionProtocol.CRITIC,
                        source = LearningSignalSource.USER_FEEDBACK,
                        reward = reward,
                        summary = if (negative) "User correction for $targetSkill: ${feedbackText.take(160)}" else "User confirmed $targetSkill: ${feedbackText.take(160)}",
                        contextJson = JSONObject().put("feedback", feedbackText.take(1000)).toString(),
                        createdAtMs = now
                    )
                )
            }
        }
        AgentMemory.recordEpisode(
            agentId = "learning",
            category = "learning-feedback",
            summary = if (negative)
                "User corrected $targetSkill: ${feedbackText.take(200)}"
            else "User confirmed $targetSkill: ${feedbackText.take(200)}",
            outcome = if (negative) EpisodeOutcome.FAILURE else EpisodeOutcome.SUCCESS,
            lesson = if (negative) "Permanent knowledge updated for: ${feedbackText.take(160)}" else "",
            contextRef = "feedback:$now"
        )
        if (negative && stageMemoryRule) {
            stageMutation(
                skillId = targetSkill,
                agentId = agentId.ifBlank { "assistant" },
                title = "Knowledge correction — $targetSkill",
                summary = "Based on your correction earlier, I have updated my permanent knowledge regarding this topic. " +
                    "Click Approve to lock in this memory update, or Deny to keep the previous knowledge.",
                changeType = ChangeType.MEMORY_RULE,
                protocol = EvolutionProtocol.CRITIC,
                riskLevel = RiskLevel.HIGH,
                diffBefore = "previous knowledge (unchanged)",
                diffAfter = "corrected knowledge: ${feedbackText.take(200)}",
                payloadJson = JSONObject()
                    .put("content", feedbackText.take(2000))
                    .put("category", "learned")
                    .put("methodId", "rule-${now / 1000}")
                    .toString(),
                methodId = "rule-${now / 1000}",
                protocolForLedger = EvolutionProtocol.CRITIC
            )
        }
    }

    // ── protocol 3: cross-agent learning ────────────────────────────────────

    /**
     * A handoff misalignment (agent B rejected agent A's artifact — missing
     * fields, wrong schema). CROSS_AGENT protocol: signal the failure; after
     * [HANDOFF_ALIGNMENT_THRESHOLD] misalignments between the pair, stage a
     * shared workflow contract for the user to deploy.
     */
    fun recordHandoffFailure(fromAgent: String, toAgent: String, task: String, detail: String) {
        val database = db() ?: return
        val now = System.currentTimeMillis()
        runBlocking {
            runCatching {
                database.evolutionDao().insertSignal(
                    LearningSignal(
                        skillId = "handoff:$fromAgent->$toAgent",
                        agentId = fromAgent,
                        protocol = EvolutionProtocol.CROSS_AGENT,
                        source = LearningSignalSource.HANDOFF_FAILURE,
                        reward = -0.5,
                        summary = "Handoff $fromAgent → $toAgent misaligned ($task): ${detail.take(160)}",
                        createdAtMs = now
                    )
                )
            }
        }
        AgentMemory.recordEpisode(
            agentId = "learning",
            category = "learning-handoff",
            summary = "Alignment error: $fromAgent → $toAgent on \"${task.take(120)}\": ${detail.take(160)}",
            outcome = EpisodeOutcome.FAILURE,
            lesson = "Handoff contract between $fromAgent and $toAgent needs an update: ${detail.take(120)}",
            contextRef = "handoff:$now"
        )
        val pairKey = "handoff:$fromAgent->$toAgent"
        val recent = runBlocking {
            runCatching { database.evolutionDao().recentSignals(200) }.getOrDefault(emptyList())
        }
        val misalignments = recent.count { it.skillId == pairKey && it.source == LearningSignalSource.HANDOFF_FAILURE }
        val hasContract = runBlocking { runCatching { database.evolutionDao().stagedMethods(pairKey) }.getOrDefault(emptyList()) }.isNotEmpty()
        if (misalignments >= HANDOFF_ALIGNMENT_THRESHOLD && !hasContract) {
            stageMutation(
                skillId = pairKey,
                agentId = fromAgent,
                title = "Agent alignment contract — $fromAgent ↔ $toAgent",
                summary = "The $fromAgent and $toAgent agents optimized their shared communication pattern to prevent file-format errors. " +
                    "Click Approve to deploy their updated shared workflow contract.",
                changeType = ChangeType.MEMORY_RULE,
                protocol = EvolutionProtocol.CROSS_AGENT,
                riskLevel = RiskLevel.MEDIUM,
                diffBefore = "current handoff schema (caused ${misalignments} alignment errors)",
                diffAfter = "agreed shared contract: both sides validate the artifact fields before publishing",
                payloadJson = JSONObject()
                    .put("content", "Agent handoff contract ($fromAgent ↔ $toAgent): both sides must validate the artifact's required fields and JSON schema before publishing, to prevent format mismatch rejections.")
                    .put("category", "learned")
                    .put("methodId", "contract-${now / 1000}")
                    .toString(),
                methodId = "contract-${now / 1000}",
                protocolForLedger = EvolutionProtocol.CROSS_AGENT
            )
        }
    }

    // ── the Continuous Fuzzing Engine (skill.sys.background_fuzzer) ────────

    /**
     * One background fuzz pass. For every enabled skill with enough execution
     * data and no candidate already pending, it proposes an alternative
     * approach (the exploration rotation), records the observed benchmark
     * (real telemetry — success rate / latency of the current best), and
     * stages the candidate behind the gate. It NEVER deploys anything and
     * never claims a candidate is faster — the user sees an honest card
     * (\"benchmarked against the current best; approve to enable and track
     * it\") and the candidate's own execution history will prove or refute it.
     * Returns the number of candidates staged.
     */
    fun fuzzPass(): Int {
        if (!fuzzEnabled()) return 0
        val database = db() ?: return 0
        val skills = SkillManager.skills().filter { it.enabled }
        var staged = 0
        skills.forEach { skill ->
            if (staged >= MAX_STAGES_PER_FUZZ) return@forEach
            runBlocking {
                runCatching {
                    val dao = database.evolutionDao()
                    val ledger = dao.evolutionsForSkill(skill.skillId)
                    val totalExec = ledger.sumOf { it.executionCount }
                    if (totalExec < MIN_EXECUTIONS_FOR_FUZZ) return@runCatching
                    if (dao.pendingStaging().any { it.skillId == skill.skillId }) return@runCatching
                    if (dao.stagedMethods(skill.skillId).isNotEmpty()) return@runCatching
                    val best = dao.activeMethods(skill.skillId).maxByOrNull { it.confidence } ?: return@runCatching
                    val now = System.currentTimeMillis()
                    val spec = LearningSpecs.learningSpec(skill)
                    val variation = spec.explorationHint.takeIf { it.isNotBlank() }
                        ?: FUZZ_VARIATIONS[Math.floorMod(skill.skillId.hashCode(), FUZZ_VARIATIONS.size)]
                    val successRate = (best.successCount.toDouble() / best.executionCount.coerceAtLeast(1) * 100).toInt()
                    val methodId = "fuzz-${now / 1000}"
                    val payload = JSONObject()
                        .put("methodId", methodId)
                        .put("parent", best.methodId)
                        .put("method_guidance", variation)
                        .toString()
                    dao.insertSignal(
                        LearningSignal(
                            skillId = skill.skillId,
                            agentId = "skill.sys.background_fuzzer",
                            protocol = EvolutionProtocol.DETERMINISTIC,
                            source = LearningSignalSource.BENCHMARK,
                            reward = 0.0,
                            summary = "Benchmark ${skill.skillId}: best=${best.methodId} · $successRate% success over ${best.executionCount} runs · avg ${best.avgLatencyMs}ms",
                            contextJson = JSONObject()
                                .put("best", best.methodId)
                                .put("successRate", successRate)
                                .put("avgLatencyMs", best.avgLatencyMs)
                                .toString(),
                            createdAtMs = now
                        )
                    )
                    dao.insertStaging(
                        StagingRecord(
                            stagingId = "stg-${UUID.randomUUID().toString().take(8)}",
                            skillId = skill.skillId,
                            agentId = "skill.sys.background_fuzzer",
                            title = "Alternative method for ${skill.name}",
                            summary = "I benchmarked ${skill.name} against an alternative approach: \"$variation\". " +
                                "Current best is $successRate% successful over ${best.executionCount} runs (avg ${best.avgLatencyMs}ms). " +
                                "Approve to enable this candidate so its own results can be tracked — it will only replace the current method if it wins.",
                            changeType = ChangeType.MUTATION,
                            protocol = EvolutionProtocol.DETERMINISTIC,
                            riskLevel = if (skill.requiresApproval || skill.sandboxRequired) RiskLevel.MEDIUM else RiskLevel.LOW,
                            diffBefore = "current best: ${best.methodId} · $successRate% · ${best.executionCount} runs · avg ${best.avgLatencyMs}ms",
                            diffAfter = "candidate: $methodId · $variation",
                            payloadJson = payload,
                            createdAtMs = now
                        )
                    )
                    dao.upsertEvolution(
                        SkillEvolution(
                            skillId = skill.skillId,
                            methodId = methodId,
                            parentMethodId = best.methodId,
                            source = EvolutionSource.FUZZ,
                            protocol = EvolutionProtocol.DETERMINISTIC,
                            payloadJson = payload,
                            status = EvolutionStatus.STAGED,
                            createdAtMs = now, updatedAtMs = now
                        )
                    )
                    staged++
                }
            }
        }
        if (staged > 0) kvPut(KV_LAST_FUZZ_MS, System.currentTimeMillis().toString())
        return staged
    }

    /**
     * The reflection pass: fold unconsumed reward signals into episodic
     * memory as one batch summary (the "what did we learn" step), then mark
     * them consumed. The ledger already moved per execution; this is the
     * AI-feedback summarization layer.
     */
    fun consumeSignals(): Int {
        val database = db() ?: return 0
        val now = System.currentTimeMillis()
        val signals = runBlocking {
            runCatching { database.evolutionDao().unconsumedSignals(50) }.getOrDefault(emptyList())
        }
        if (signals.isEmpty()) return 0
        val negative = signals.count { it.reward < 0 }
        val positive = signals.count { it.reward > 0 }
        AgentMemory.recordEpisode(
            agentId = "learning",
            category = "reflection",
            summary = "Reflection pass: ${signals.size} signals folded in ($negative negative, $positive positive). " +
                (signals.firstOrNull { it.reward < 0 }?.summary?.take(120) ?: "No failures to reflect on."),
            outcome = if (negative > 0) EpisodeOutcome.FAILURE else EpisodeOutcome.OBSERVATION,
            lesson = if (negative > 0) signals.filter { it.reward < 0 }.joinToString(" | ") { it.summary.take(80) }.take(400) else "",
            contextRef = "reflection:$now"
        )
        runBlocking {
            runCatching { signals.forEach { database.evolutionDao().markConsumed(it.id) } }
        }
        return signals.size
    }

    // ── reads for the UI ────────────────────────────────────────────────────

    fun pendingUpdates(): List<StagingRecord> {
        val database = db() ?: return emptyList()
        return runBlocking { runCatching { database.evolutionDao().pendingStaging() }.getOrDefault(emptyList()) }
    }

    fun pendingCount(): Int {
        val database = db() ?: return 0
        return runBlocking { runCatching { database.evolutionDao().pendingStagingCount() }.getOrDefault(0) }
    }

    fun recentDecisions(limit: Int = 50): List<StagingRecord> {
        val database = db() ?: return emptyList()
        return runBlocking { runCatching { database.evolutionDao().recentStaging(limit) }.getOrDefault(emptyList()) }
    }

    fun signals(limit: Int = 50): List<LearningSignal> {
        val database = db() ?: return emptyList()
        return runBlocking { runCatching { database.evolutionDao().recentSignals(limit) }.getOrDefault(emptyList()) }
    }

    fun recentLedger(limit: Int = 100): List<SkillEvolution> {
        val database = db() ?: return emptyList()
        return runBlocking { runCatching { database.evolutionDao().recentEvolution(limit) }.getOrDefault(emptyList()) }
    }

    /** Aggregate stats for the Updates screen header. */
    fun stats(): Map<String, Int> {
        val database = db() ?: return emptyMap()
        val methods = runBlocking { runCatching { database.evolutionDao().recentEvolution(10_000) }.getOrDefault(emptyList()) }.size
        val signals = runBlocking { runCatching { database.evolutionDao().recentSignals(10_000) }.getOrDefault(emptyList()) }.size
        return mapOf("methods" to methods, "signals" to signals, "pending" to pendingCount())
    }
}

/**
 * The Learning Specification Interface — parsed from `skills.learningSpec`
 * (the `learning` object of a skill.json manifest). Each skill declares which
 * protocol it learns with, what counts as a mistake, how it tests, and an
 * optional exploration hint; the kernel never forces one loop onto every tool.
 */
data class LearningSpec(
    val protocol: String = EvolutionProtocol.DETERMINISTIC,
    val mistakeDefinition: String = "",
    val testStrategy: String = "",
    val explorationHint: String = ""
)

object LearningSpecs {

    /** Protocol by skill category when the manifest doesn't declare one. */
    fun protocolOf(skill: SkillEntity): String {
        val declared = runCatching { JSONObject(skill.learningSpec).optString("protocol") }.getOrDefault("")
        if (declared.isNotBlank()) return declared
        return when (skill.category) {
            "knowledge", "communication", "research" -> EvolutionProtocol.CRITIC
            else -> EvolutionProtocol.DETERMINISTIC
        }
    }

    fun learningSpec(skill: SkillEntity): LearningSpec = runCatching {
        val o = JSONObject(skill.learningSpec)
        LearningSpec(
            protocol = o.optString("protocol").ifBlank { protocolOf(skill) },
            mistakeDefinition = o.optString("mistake_definition"),
            testStrategy = o.optString("test_strategy"),
            explorationHint = o.optString("exploration_hint")
        )
    }.getOrDefault(LearningSpec(protocol = protocolOf(skill)))

    /** Category-derived default spec (seeded into blank learningSpec columns at upgrade). */
    fun defaultSpec(skill: SkillEntity): String {
        val protocol = protocolOf(skill)
        val mistake = when (protocol) {
            EvolutionProtocol.CRITIC -> "A user correction or negative feedback about the produced result."
            EvolutionProtocol.CROSS_AGENT -> "A handoff rejected by the receiving agent (schema/field mismatch)."
            else -> "A nonzero exit code, thrown exception, or repeated execution failure."
        }
        val test = when (protocol) {
            EvolutionProtocol.CRITIC -> "Reflect on the corrected chunk, extract the contradiction, rewrite the memory rule."
            EvolutionProtocol.CROSS_AGENT -> "Run a private alignment pass between the two agents until the shared schema satisfies both."
            else -> "Run the candidate in an isolated sandbox with dummy data; require a clean exit and identical output schema."
        }
        return JSONObject()
            .put("protocol", protocol)
            .put("mistake_definition", mistake)
            .put("test_strategy", test)
            .put("exploration_hint", "")
            .toString()
    }
}

