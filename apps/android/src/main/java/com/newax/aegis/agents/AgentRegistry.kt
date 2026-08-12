package com.newax.aegis.agents

import android.content.Context
import android.net.Uri
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.AgentEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * The multi-agent management registry (docs/AGENTS_DESIGN.md): import agent
 * packages (zip with an `agent.json` manifest), enable/disable, upgrade,
 * uninstall — all inside the assistant app.
 *
 * Package layout:
 *   agent.zip
 *     agent.json        — { id, name, version, description, category,
 *                          keywords: [...], skills: [...] }
 *     <skill files…>    — extracted under filesDir/agents/<id>/
 *
 * Security (R12 — untrusted input is data, never instruction): the zip is a
 * hostile input. Every entry name is validated — absolute paths, drive
 * letters, backslashes, and any `..` segment are REJECTED (zip-slip), and the
 * resolved target must stay inside the package directory. The manifest is
 * parsed with defaults for every optional field and rejected entirely when
 * required fields are missing or malformed.
 *
 * Upgrade = same id with a higher version: the old package directory is
 * replaced, the row is upserted, and the enabled flag is preserved. Disabled
 * agents never route. Built-ins can be disabled but never uninstalled.
 *
 * Device-local by design (no sync columns): the mesh syncs what agents
 * produce (episodes, handoffs, library), never the binaries.
 */
object AgentRegistry {

    private const val MANIFEST_FILE = "agent.json"
    private const val SOURCE_BUILTIN = "builtin"
    private const val SOURCE_ZIP = "zip"

    @Volatile
    private var appContext: Context? = null

    /** Call once at app start (after NewaxDatabase.init). */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ensureSeeded()
    }

    private fun context(): Context = requireNotNull(appContext) { "AgentRegistry.init(context) not called" }

    private fun agentsRoot(): File = File(context().filesDir, "agents").apply { mkdirs() }

    // ── manifest model ───────────────────────────────────────────────────────

    data class AgentManifest(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val category: String,
        val keywords: List<String>,
        val skills: List<String>
    )

    /** Null on any missing/malformed required field — never a partial agent. */
    fun parseManifest(json: String): AgentManifest? = runCatching {
        val o = JSONObject(json)
        val id = o.optString("id").trim()
        val name = o.optString("name").trim()
        val version = o.optString("version").trim()
        val description = o.optString("description").trim()
        val category = o.optString("category").trim()
        if (id.isEmpty() || name.isEmpty() || version.isEmpty() || category.isEmpty()) return null
        val arr = { key: String -> o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it).trim() } } ?: emptyList() }
        AgentManifest(id, name, version, description, category, arr("keywords"), arr("skills"))
    }.getOrNull()

    // ── seeds ────────────────────────────────────────────────────────────────

    /** The four built-in roles — always present, disable-able, never uninstall-able. */
    private val BUILTINS = listOf(
        AgentManifest(
            id = "coding", name = "Coding Agent", version = "1.0.0",
            description = "Writes, debugs, and reviews code. Dominates whenever the task is about code, scripts, bugs, or programming.",
            category = "coding",
            keywords = listOf("code", "coding", "program", "programming", "script", "bug", "debug", "compile", "function", "class", "kotlin", "python", "java", "javascript", "api", "write code", "fix", "refactor", "test"),
            skills = listOf("run_shell", "open_app", "system_query")
        ),
        AgentManifest(
            id = "planning", name = "Planning Agent", version = "1.0.0",
            description = "Breaks goals into steps, sequences work, and coordinates. Dominates for planning, strategy, schedules, and multi-step tasks.",
            category = "planning",
            keywords = listOf("plan", "planning", "strategy", "step", "steps", "schedule", "roadmap", "organize", "break down", "sequence", "priority", "prioritize", "outline", "first", "then", "next"),
            skills = listOf("run_goal", "system_query")
        ),
        AgentManifest(
            id = "research", name = "Research Agent", version = "1.0.0",
            description = "Gathers and verifies information. Dominates for research, analysis, summaries, facts, and answering questions.",
            category = "research",
            keywords = listOf("research", "analyze", "analysis", "summarize", "summary", "find", "look up", "search", "fact", "facts", "explain", "investigate", "compare", "review", "information", "details", "what is", "how does", "why"),
            skills = listOf("system_query", "open_app")
        ),
        AgentManifest(
            id = "organizer", name = "Organizer Agent", version = "1.0.0",
            description = "Tracks tasks, reminders, contacts, and context. Dominates for to-dos, reminders, appointments, and keeping things in order.",
            category = "organizer",
            keywords = listOf("remind", "reminder", "todo", "to-do", "task", "tasks", "appointment", "meeting", "schedule", "contact", "contacts", "organize", "inbox", "follow up", "deadline", "due"),
            skills = listOf("open_app", "system_query")
        )
    )

    fun ensureSeeded() {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking {
            runCatching {
                val dao = db.agentRegistryDao()
                if (dao.count() > 0) return@runCatching
                val now = System.currentTimeMillis()
                BUILTINS.forEach { m ->
                    dao.upsert(
                        AgentEntity(
                            agentId = m.id, name = m.name, version = m.version, description = m.description,
                            category = m.category, keywords = m.keywords.joinToString(","), skills = m.skills.joinToString(","),
                            enabled = true, source = SOURCE_BUILTIN, installedAtMs = now
                        )
                    )
                }
            }
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun agents(): List<AgentEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.agentRegistryDao().all() }.getOrDefault(emptyList()) }
    }

    fun enabledAgents(): List<AgentEntity> {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { db.agentRegistryDao().enabled() }.getOrDefault(emptyList()) }
    }

    fun setEnabled(agentId: String, on: Boolean) {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return
        runBlocking { runCatching { db.agentRegistryDao().setEnabled(agentId, on) } }
    }

    /** Only imported agents can be uninstalled; built-ins are disabled instead. */
    fun uninstall(agentId: String): Boolean {
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return false
        val agent = runBlocking { runCatching { db.agentRegistryDao().byId(agentId) }.getOrNull() } ?: return false
        if (agent.source == SOURCE_BUILTIN) return false
        val removed = runBlocking { runCatching { db.agentRegistryDao().delete(agentId) }.getOrDefault(0) } > 0
        if (removed) {
            runCatching { File(agent.packageDir).deleteRecursively() }
        }
        return removed
    }

    /**
     * Import (or upgrade) an agent from a zip picked via SAF. Returns a
     * human message: success ("installed"/"upgraded"), or why it failed.
     * Never throws — every failure is a named result.
     */
    fun importAgent(uri: Uri): String {
        val context = context()
        val manifestAndFiles = extractZip(context, uri) ?: return "Import failed — not a valid agent zip (needs agent.json)"
        val (manifest, packageDir) = manifestAndFiles
        val db = runCatching { NewaxDatabase.get }.getOrNull() ?: return "Import failed — database not ready"

        val existing = runBlocking { runCatching { db.agentRegistryDao().byId(manifest.id) }.getOrNull() }
        val upgraded = existing != null && compareVersions(manifest.version, existing.version) > 0
        val installed = runBlocking {
            runCatching {
                db.agentRegistryDao().upsert(
                    AgentEntity(
                        agentId = manifest.id, name = manifest.name, version = manifest.version,
                        description = manifest.description, category = manifest.category,
                        keywords = manifest.keywords.joinToString(","), skills = manifest.skills.joinToString(","),
                        enabled = existing?.enabled ?: true,
                        source = SOURCE_ZIP, installedAtMs = System.currentTimeMillis(),
                        packageDir = packageDir.absolutePath
                    )
                )
            }.getOrNull()
        }
        if (installed == null) {
            runCatching { packageDir.deleteRecursively() }
            return "Import failed — could not register agent"
        }
        return if (upgraded) "Upgraded ${manifest.name} to v${manifest.version}" else "Installed ${manifest.name} v${manifest.version}"
    }

    /** Compare dotted versions numerically: 1.2.10 > 1.2.9. */
    fun compareVersions(a: String, b: String): Int {
        val ap = a.split('.').map { it.toIntOrNull() ?: 0 }
        val bp = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val diff = (ap.getOrElse(i) { 0 }) - (bp.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * Read the zip via the shared zip-slip-safe extractor, parse the
     * manifest, and move the validated package into its permanent home.
     * Null on any security violation or a missing/invalid manifest.
     */
    private fun extractZip(context: Context, uri: Uri): Pair<AgentManifest, File>? {
        val entries = ZipPackages.extractValidated(context, uri) ?: return null
        val manifest = entries.firstOrNull { it.first == MANIFEST_FILE }
            ?.let { parseManifest(String(it.second, Charsets.UTF_8)) }
            ?: return null

        val root = File(agentsRoot(), "import-tmp-${System.currentTimeMillis()}").apply { mkdirs() }
        entries.filter { it.first != MANIFEST_FILE }.forEach { (name, bytes) ->
            val target = File(root, name)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }

        // Move the validated package into its permanent home.
        val dest = File(agentsRoot(), manifest.id)
        runCatching { dest.deleteRecursively() }
        if (!root.renameTo(dest)) {
            // renameTo can fail across some FS setups — fall back to copy.
            root.copyRecursively(dest, overwrite = true)
            root.deleteRecursively()
        }
        return manifest to dest
    }
}
