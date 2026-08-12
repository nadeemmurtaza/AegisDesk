package com.newax.aegis.agents

import android.content.Context
import android.net.Uri
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.AgentSkill
import com.newax.aegis.db.entity.SkillEntity
import com.newax.aegis.db.entity.SkillSet
import com.newax.aegis.db.entity.SkillSetMember
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * The skills management system (docs/AGENTS_DESIGN.md §skills), aligned with
 * the agents registry:
 *
 *  - skills are SHARED capability packages — any number of agents can be
 *    granted the same skill (many-to-many through `agent_skills`),
 *  - the permission model: a grant row (agent → skill) IS the permission;
 *    absence = denied, and [canUse] also requires both the skill and the
 *    agent to be enabled,
 *  - skill sets are named bundles for granting/revoking in groups,
 *  - import/upgrade mirrors agents: zip with `skill.json`
 *    ({ id, name, version, description, category }), zip-slip-safe via the
 *    shared [ZipPackages] extractor.
 *
 * Seeds at startup: the seven capability skills (matching the mesh command
 * classes) with default grants for the built-in agents, and the legacy
 * `agents.skills` column (comma strings from the v15 slice) is migrated into
 * real grant rows. Device-local — no sync columns.
 */
object SkillManager {

    private const val MANIFEST_FILE = "skill.json"
    private const val SOURCE_BUILTIN = "builtin"
    private const val SOURCE_ZIP = "zip"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ensureSeeded()
    }

    private fun context(): Context = requireNotNull(appContext) { "SkillManager.init(context) not called" }

    private fun skillsRoot(): File = File(context().filesDir, "skills").apply { mkdirs() }

    /** Built-in skills — the mesh command classes + the desktop skill ladder. */
    private data class SkillSeed(
        val id: String, val name: String, val description: String, val category: String,
        val capability: String, val sandboxRequired: Boolean, val requiresApproval: Boolean,
        val risks: String, val defaultAgents: List<String>,
        /** "agent" (needs a grant) or "global" (implicitly granted to every agent). */
        val scope: String = "agent"
    )

    private val SEEDS = listOf(
        SkillSeed("run_shell", "Shell runner", "Execute a bounded shell command with timeout and output caps.", "automation",
            "code_execution", sandboxRequired = true, requiresApproval = true,
            "Executes commands; host-level impact — sandboxed + human-approved only", listOf("coding")),
        SkillSeed("open_app", "App launcher", "Launch or activate an app by name/package.", "automation",
            "app_control", sandboxRequired = false, requiresApproval = false,
            "Launches apps on the device", listOf("coding", "research", "organizer")),
        SkillSeed("open_file", "File opener", "Open a file in its default application.", "files",
            "file_access", sandboxRequired = false, requiresApproval = false,
            "Opens files via the OS handler", emptyList()),
        SkillSeed("browse_files", "File browser", "List and inspect files within the confined base directory.", "files",
            "file_access", sandboxRequired = false, requiresApproval = false,
            "Reads the confined base directory only", emptyList()),
        SkillSeed("send_email", "Email sender", "Compose and send an email message.", "communication",
            "communication", sandboxRequired = false, requiresApproval = true,
            "Sends outbound mail — always human-approved", emptyList()),
        SkillSeed("system_query", "Knowledge query", "Search memory, graph, contacts, and apps for information.", "knowledge",
            "knowledge", sandboxRequired = false, requiresApproval = false,
            "Reads local knowledge stores only", listOf("research", "organizer", "planning")),
        SkillSeed("run_goal", "Goal runner", "Execute a goal plan through the planner and executor.", "planning",
            "planning", sandboxRequired = false, requiresApproval = false,
            "Runs goal plans through the policy-gated executor", listOf("planning"))
    )

    /** Default named bundles created at seed time. */
    /**
     * The universal SYSTEM skills (docs/AGENTS_DESIGN.md §runtime) — the
     * standard agent capabilities every agent shares, extracted OUT of agent
     * code into decoupled system skills under `app/skills/system/`:
     *  - skill.sys.mcp_stream — live token/phase streaming to the UI (the MCP
     *    dispatcher, [AgentStream]),
     *  - skill.sys.serialize_state — freeze/thaw session serialization
     *    ([StateArchiver]),
     *  - skill.sys.health_audit — integrity audit + quarantine ([AgentRuntimeEngine]),
     *  - skill.sys.task_control — abort/suspend/resume ([AgentRuntimeEngine]).
     * All are scope = "global": granted to every active agent implicitly — the
     * permission controller bypasses the restrictive whitelist for these core
     * utilities while shell/files skills stay "agent"-scoped and restricted.
     */
    private val SYSTEM_SKILLS = listOf(
        SkillSeed(
            "skill.sys.mcp_stream", "Stream Dispatcher",
            "Broadcast live tokens, phases, and progress to the assistant UI over the structured stream protocol.",
            "system", "system", sandboxRequired = false, requiresApproval = false,
            "Read-only UI telemetry", emptyList(), scope = "global"),
        SkillSeed(
            "skill.sys.serialize_state", "State Archiver",
            "Freeze an agent session (task, context, result) to app-private disk and restore it later.",
            "system", "system", sandboxRequired = false, requiresApproval = false,
            "Writes app-private state files only", emptyList(), scope = "global"),
        SkillSeed(
            "skill.sys.health_audit", "Health Audit",
            "Verify agent integrity — database, manifest, package, skills, sessions — and quarantine a faulted agent.",
            "system", "system", sandboxRequired = false, requiresApproval = false,
            "May disable a faulted agent until a human restores it", emptyList(), scope = "global"),
        SkillSeed(
            "skill.sys.task_control", "Task Control",
            "Abort, suspend, or resume a running agent session from the runtime.",
            "system", "system", sandboxRequired = false, requiresApproval = false,
            "Stops a running session", emptyList(), scope = "global"),
        SkillSeed(
            "skill.sys.self_learn", "Self-Learning Ledger",
            "The RLAIF-E engine: per-skill evolution ledger, Bayesian confidence scoring, and exploit/explore method selection from execution outcomes.",
            "system", "system", sandboxRequired = false, requiresApproval = false,
            "Reads/writes the device-local evolution ledger", emptyList(), scope = "global"),
        SkillSeed(
            "skill.sys.background_fuzzer", "Background Fuzzer",
            "The continuous exploration service: when the device is idle, duplicates active skills, proposes alternative approaches, benchmarks observed stats, and stages candidates behind the user approval gate.",
            "system", "system", sandboxRequired = false, requiresApproval = false,
            "Stages candidate mutations for user approval — never deploys without the gate", emptyList(), scope = "global")
    )

    private val DEFAULT_SETS = listOf(
        Triple("automation", "Automation", "Shell + app control skills"),
        Triple("knowledge", "Knowledge", "Query and research skills"),
        Triple("communication", "Communication", "Outbound messaging skills"),
        Triple("files", "Files", "File access skills")
    )

    private val DEFAULT_SET_MEMBERS = mapOf(
        "automation" to listOf("run_shell", "open_app"),
        "knowledge" to listOf("system_query", "open_file", "browse_files"),
        "communication" to listOf("send_email"),
        "files" to listOf("open_file", "browse_files")
    )

    fun ensureSeeded() {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking {
            runCatching {
                val dao = db.skillManagerDao()
                if (dao.allSkills().isEmpty()) {
                    val now = System.currentTimeMillis()
                    SEEDS.forEach { s ->
                        dao.upsertSkill(
                            SkillEntity(
                                skillId = s.id, name = s.name, description = s.description,
                                category = s.category, version = "1.0.0",
                                capability = s.capability,
                                sandboxRequired = s.sandboxRequired,
                                requiresApproval = s.requiresApproval,
                                risks = s.risks,
                                enabled = true, source = SOURCE_BUILTIN, installedAtMs = now
                            )
                        )
                        s.defaultAgents.forEach { agentId -> dao.grantSkill(AgentSkill(agentId = agentId, skillId = s.id, grantedAtMs = now)) }
                    }
                    // Agent capabilities (what each agent knows how to do) — the
                    // capability bridge the guard checks against. Only fills
                    // blank values (never overwrites a user's customization).
                    db.agentRegistryDao().all().forEach { agent ->
                        if (agent.capabilities.isBlank()) {
                            val caps = when (agent.agentId) {
                                "coding" -> "code_execution,app_control,planning"
                                "planning" -> "planning,knowledge"
                                "research" -> "knowledge,app_control,communication"
                                "organizer" -> "knowledge,app_control,communication,planning"
                                else -> "knowledge"
                            }
                            db.agentRegistryDao().upsert(agent.copy(capabilities = caps))
                        }
                    }
                    DEFAULT_SETS.forEach { (id, name, description) ->
                        dao.upsertSet(SkillSet(setId = id, name = name, description = description, createdAtMs = now))
                    }
                    DEFAULT_SET_MEMBERS.forEach { (setId, skills) ->
                        skills.forEach { dao.addToSet(SkillSetMember(setId = setId, skillId = it)) }
                    }
                }
                // System skills seed idempotently — OUTSIDE the isEmpty() gate, so an
                // existing install that upgrades into v18 still gets them.
                SYSTEM_SKILLS.forEach { s ->
                    if (runCatching { dao.skillById(s.id) }.getOrNull() == null) {
                        dao.upsertSkill(
                            SkillEntity(
                                skillId = s.id, name = s.name, description = s.description,
                                category = s.category, version = "1.0.0",
                                capability = s.capability,
                                sandboxRequired = s.sandboxRequired,
                                requiresApproval = s.requiresApproval,
                                risks = s.risks, scope = s.scope,
                                enabled = true, source = SOURCE_BUILTIN, installedAtMs = System.currentTimeMillis()
                            )
                        )
                    }
                }
                // Migrate the legacy agents.skills comma column (v15) into real grants.
                db.agentRegistryDao().all().forEach { agent ->
                    val existing = dao.grantedSkillIds(agent.agentId).toSet()
                    agent.skills.split(',').map { it.trim() }.filter { it.isNotBlank() && it !in existing }.forEach { skillId ->
                        if (dao.skillById(skillId) != null) dao.grantSkill(AgentSkill(agentId = agent.agentId, skillId = skillId))
                    }
                }
                // RLAIF-E (schema v19): backfill the per-skill Learning
                // Specification Interface for skills upgraded from v18 (their
                // learningSpec column defaults to '{}'). Idempotent — only
                // blank specs are filled, never a user's customization.
                dao.allSkills().forEach { skill ->
                    if (skill.learningSpec.isBlank() || skill.learningSpec == "{}") {
                        dao.upsertSkill(skill.copy(learningSpec = LearningSpecs.defaultSpec(skill)))
                    }
                }
            }
        }
    }

    // ── skills ──────────────────────────────────────────────────────────────

    fun skills(): List<SkillEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().allSkills() }.getOrDefault(emptyList()) }
    }

    fun setEnabled(skillId: String, on: Boolean) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().setSkillEnabled(skillId, on) } }
    }

    fun uninstall(skillId: String): Boolean {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return false
        val skill = runBlocking { runCatching { db.skillManagerDao().skillById(skillId) }.getOrNull() } ?: return false
        if (skill.source == SOURCE_BUILTIN) return false
        val removed = runBlocking { runCatching { db.skillManagerDao().deleteSkill(skillId) }.getOrDefault(0) } > 0
        if (removed) runCatching { File(skill.packageDir).deleteRecursively() }
        return removed
    }

    /** Import or upgrade a skill zip (skill.json manifest). Never throws. */
    fun importSkill(uri: Uri): String {
        val context = context()
        val entries = ZipPackages.extractValidated(context, uri) ?: return "Import failed — not a valid skill zip (needs skill.json)"
        val manifest = entries.firstOrNull { it.first == MANIFEST_FILE }
            ?.let { parseSkillManifest(String(it.second, Charsets.UTF_8)) }
            ?: return "Import failed — invalid skill.json manifest"
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return "Import failed — database not ready"

        val root = File(skillsRoot(), "import-tmp-${System.currentTimeMillis()}").apply { mkdirs() }
        entries.filter { it.first != MANIFEST_FILE }.forEach { (name, bytes) ->
            val target = File(root, name)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        val dest = File(skillsRoot(), manifest.id)
        runCatching { dest.deleteRecursively() }
        if (!root.renameTo(dest)) {
            root.copyRecursively(dest, overwrite = true)
            root.deleteRecursively()
        }

        val existing = runBlocking { runCatching { db.skillManagerDao().skillById(manifest.id) }.getOrNull() }
        val upgraded = existing != null && AgentRegistry.compareVersions(manifest.version, existing.version) > 0
        runBlocking {
            runCatching {
                db.skillManagerDao().upsertSkill(
                    SkillEntity(
                        skillId = manifest.id, name = manifest.name, version = manifest.version,
                        description = manifest.description, category = manifest.category,
                        capability = manifest.capability, toolSchema = manifest.toolSchema,
                        sandboxRequired = manifest.sandboxRequired, requiresApproval = manifest.requiresApproval,
                        risks = manifest.risks, learningSpec = manifest.learningSpec,
                        enabled = existing?.enabled ?: true,
                        source = SOURCE_ZIP, installedAtMs = System.currentTimeMillis(),
                        packageDir = dest.absolutePath
                    )
                )
            }
        }
        return if (upgraded) "Upgraded ${manifest.name} to v${manifest.version}" else "Installed ${manifest.name} v${manifest.version}"
    }

    /**
     * The individual skill schema — `app/skills/<id>/manifest.json`
     * (docs/AGENTS_DESIGN.md §schemas). Snake_case keys per the package
     * spec; every optional field defaults safely.
     */
    data class SkillManifest(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val category: String,
        val capability: String,
        val toolSchema: String,
        val sandboxRequired: Boolean,
        val requiresApproval: Boolean,
        val risks: String,
        /** The Learning Specification Interface (docs/AGENTS_DESIGN.md §evolution). */
        val learningSpec: String = "{}"
    )

    fun parseSkillManifest(json: String): SkillManifest? = runCatching {
        val o = JSONObject(json)
        val id = o.optString("id").trim()
        val name = o.optString("name").trim()
        val version = o.optString("version").trim()
        val description = o.optString("description").trim()
        val category = o.optString("category").trim()
        if (id.isEmpty() || name.isEmpty() || version.isEmpty()) return null
        SkillManifest(
            id = id, name = name, version = version, description = description,
            category = category.ifBlank { "custom" },
            capability = o.optString("capability").trim(),
            toolSchema = o.optJSONObject("tool_schema")?.toString() ?: "{}",
            sandboxRequired = o.optBoolean("sandbox_required", false),
            requiresApproval = o.optBoolean("requires_approval", false),
            risks = o.optString("risks").trim(),
            learningSpec = o.optJSONObject("learning")?.toString() ?: "{}"
        )
    }.getOrNull()

    /**
     * Import a skill-set file — `app/skillsets/<name>.json`
     * (docs/AGENTS_DESIGN.md §schemas): { id, name, description, skills: [...] }.
     */
    fun importSkillSet(uri: Uri): String {
        val context = context()
        val json = runCatching {
            context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull() ?: return "Import failed — could not read the set file"
        val parsed = runCatching {
            val o = JSONObject(json)
            val id = o.optString("id").trim()
            val name = o.optString("name").trim()
            val skills = o.optJSONArray("skills")?.let { a -> (0 until a.length()).map { a.getString(it).trim() } } ?: emptyList()
            Triple(id, name, skills)
        }.getOrNull() ?: return "Import failed — not a valid skill-set JSON"
        val (id, name, skills) = parsed
        if (id.isEmpty() || name.isEmpty()) return "Import failed — set needs id and name"
        createSet(id, name, oDescription(json))
        skills.forEach { addToSet(id, it) }
        return "Imported skill set $name (${skills.size} skills)"
    }

    private fun oDescription(json: String): String =
        runCatching { JSONObject(json).optString("description").trim() }.getOrDefault("")

    /** The tool schemas an agent may use, exposed to the runtime (the model's tool list). */
    fun toolSchemasForAgent(agentId: String): List<String> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking {
            runCatching {
                db.skillManagerDao().skillsForAgent(agentId).map { it.toolSchema }.filter { it.isNotBlank() && it != "{}" }
            }.getOrDefault(emptyList())
        }
    }

    /** What one agent knows how to do — the capability set the guard bridges against. */
    fun capabilitiesOf(agentId: String): Set<String> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptySet()
        val agent = runBlocking { runCatching { db.agentRegistryDao().byId(agentId) }.getOrNull() } ?: return emptySet()
        return agent.capabilities.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    // ── permissions (which agent may use which skill) ───────────────────────

    /**
     * The permission primitive: granted AND skill enabled (agent enabled is
     * checked by routing) — except GLOBAL-scope system skills, which every
     * agent may use without a grant row (the zero-policy-maintenance bypass).
     */
    fun canUse(agentId: String, skillId: String): Boolean {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return false
        return runBlocking {
            val skill = runCatching { db.skillManagerDao().skillById(skillId) }.getOrNull() ?: return@runBlocking false
            if (!skill.enabled) return@runBlocking false
            if (skill.scope == "global") return@runBlocking true
            runCatching { db.skillManagerDao().canUseCount(agentId, skillId) }.getOrDefault(0) > 0
        }
    }

    fun grant(agentId: String, skillId: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().grantSkill(AgentSkill(agentId = agentId, skillId = skillId)) } }
    }

    fun revoke(agentId: String, skillId: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().revokeSkill(agentId, skillId) } }
    }

    fun skillsForAgent(agentId: String): List<SkillEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().skillsForAgent(agentId) }.getOrDefault(emptyList()) }
    }

    fun grantedSkillIds(agentId: String): Set<String> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptySet()
        return runBlocking { runCatching { db.skillManagerDao().grantedSkillIds(agentId) }.getOrDefault(emptyList()) }.toSet()
    }

    // ── skill sets ──────────────────────────────────────────────────────────

    fun sets(): List<SkillSet> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().allSets() }.getOrDefault(emptyList()) }
    }

    fun createSet(setId: String, name: String, description: String) {
        if (setId.isBlank() || name.isBlank()) return
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().upsertSet(SkillSet(setId = setId, name = name, description = description)) } }
    }

    fun deleteSet(setId: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().deleteSet(setId) } }
    }

    fun addToSet(setId: String, skillId: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().addToSet(SkillSetMember(setId = setId, skillId = skillId)) } }
    }

    fun removeFromSet(setId: String, skillId: String) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().removeFromSet(setId, skillId) } }
    }

    fun skillsInSet(setId: String): List<SkillEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().skillsInSet(setId) }.getOrDefault(emptyList()) }
    }
}
