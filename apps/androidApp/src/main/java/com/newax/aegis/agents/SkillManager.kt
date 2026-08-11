package com.newax.aegis.agents

import android.content.Context
import android.net.Uri
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.SkillEntity
import com.newax.aegis.db.entity.SkillSet
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
        val defaultAgents: List<String>
    )

    private val SEEDS = listOf(
        SkillSeed("run_shell", "Shell runner", "Execute a bounded shell command with timeout and output caps.", "automation", listOf("coding")),
        SkillSeed("open_app", "App launcher", "Launch or activate an app by name/package.", "automation", listOf("coding", "research", "organizer")),
        SkillSeed("open_file", "File opener", "Open a file in its default application.", "files", emptyList()),
        SkillSeed("browse_files", "File browser", "List and inspect files within the confined base directory.", "files", emptyList()),
        SkillSeed("send_email", "Email sender", "Compose and send an email message.", "communication", emptyList()),
        SkillSeed("system_query", "Knowledge query", "Search memory, graph, contacts, and apps for information.", "knowledge", listOf("research", "organizer", "planning")),
        SkillSeed("run_goal", "Goal runner", "Execute a goal plan through the planner and executor.", "planning", listOf("planning"))
    )

    /** Default named bundles created at seed time. */
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
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
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
                                enabled = true, source = SOURCE_BUILTIN, installedAtMs = now
                            )
                        )
                        s.defaultAgents.forEach { agentId -> dao.grantSkill(agentId, s.id, now) }
                    }
                    DEFAULT_SETS.forEach { (id, name, description) ->
                        dao.upsertSet(SkillSet(setId = id, name = name, description = description, createdAtMs = now))
                    }
                    DEFAULT_SET_MEMBERS.forEach { (setId, skills) ->
                        skills.forEach { dao.addToSet(setId, it) }
                    }
                }
                // Migrate the legacy agents.skills comma column (v15) into real grants.
                db.agentRegistryDao().all().forEach { agent ->
                    val existing = dao.grantedSkillIds(agent.agentId).toSet()
                    agent.skills.split(',').map { it.trim() }.filter { it.isNotBlank() && it !in existing }.forEach { skillId ->
                        if (dao.skillById(skillId) != null) dao.grantSkill(agent.agentId, skillId)
                    }
                }
            }
        }
    }

    // ── skills ──────────────────────────────────────────────────────────────

    fun skills(): List<SkillEntity> {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().allSkills() }.getOrDefault(emptyList()) }
    }

    fun setEnabled(skillId: String, on: Boolean) {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().setSkillEnabled(skillId, on) } }
    }

    fun uninstall(skillId: String): Boolean {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return false
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
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return "Import failed — database not ready"

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
                        enabled = existing?.enabled ?: true,
                        source = SOURCE_ZIP, installedAtMs = System.currentTimeMillis(),
                        packageDir = dest.absolutePath
                    )
                )
            }
        }
        return if (upgraded) "Upgraded ${manifest.name} to v${manifest.version}" else "Installed ${manifest.name} v${manifest.version}"
    }

    private fun parseSkillManifest(json: String): SkillEntity? = runCatching {
        val o = JSONObject(json)
        val id = o.optString("id").trim()
        val name = o.optString("name").trim()
        val version = o.optString("version").trim()
        val description = o.optString("description").trim()
        val category = o.optString("category").trim()
        if (id.isEmpty() || name.isEmpty() || version.isEmpty()) return null
        SkillEntity(
            skillId = id, name = name, version = version, description = description,
            category = category.ifBlank { "custom" }, source = SOURCE_ZIP,
            installedAtMs = System.currentTimeMillis()
        )
    }.getOrNull()

    // ── permissions (which agent may use which skill) ───────────────────────

    /** The permission primitive: granted AND skill enabled (agent enabled is checked by routing). */
    fun canUse(agentId: String, skillId: String): Boolean {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return false
        return runBlocking { runCatching { db.skillManagerDao().canUseCount(agentId, skillId) }.getOrDefault(0) } > 0
    }

    fun grant(agentId: String, skillId: String) {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().grantSkill(agentId, skillId) } }
    }

    fun revoke(agentId: String, skillId: String) {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().revokeSkill(agentId, skillId) } }
    }

    fun skillsForAgent(agentId: String): List<SkillEntity> {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().skillsForAgent(agentId) }.getOrDefault(emptyList()) }
    }

    fun grantedSkillIds(agentId: String): Set<String> {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return emptySet()
        return runBlocking { runCatching { db.skillManagerDao().grantedSkillIds(agentId) }.getOrDefault(emptyList()) }.toSet()
    }

    // ── skill sets ──────────────────────────────────────────────────────────

    fun sets(): List<SkillSet> {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().allSets() }.getOrDefault(emptyList()) }
    }

    fun createSet(setId: String, name: String, description: String) {
        if (setId.isBlank() || name.isBlank()) return
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().upsertSet(SkillSet(setId = setId, name = name, description = description)) } }
    }

    fun deleteSet(setId: String) {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().deleteSet(setId) } }
    }

    fun addToSet(setId: String, skillId: String) {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().addToSet(setId, skillId) } }
    }

    fun removeFromSet(setId: String, skillId: String) {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.skillManagerDao().removeFromSet(setId, skillId) } }
    }

    fun skillsInSet(setId: String): List<SkillEntity> {
        val db = runCatching { AegisDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.skillManagerDao().skillsInSet(setId) }.getOrDefault(emptyList()) }
    }
}
