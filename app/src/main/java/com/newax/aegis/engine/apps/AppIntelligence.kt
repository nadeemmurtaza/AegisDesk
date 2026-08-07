package com.newax.aegis.engine.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.UiProcedure

object AppIntelligence {

    enum class Strategy { LAUNCH_INTENT, DEEP_LINK, ACTION_INTENT, UI_PROCEDURE, NOT_FOUND }

    data class Resolution(
        val strategy: Strategy,
        val packageName: String,
        val intent: Intent? = null,
        val procedure: UiProcedure? = null
    )

    // ── Resolve task → cheapest execution path ────────────────────────────────

    fun resolve(
        db: AegisDatabase,
        context: Context,
        capability: AppCapability,
        packageHint: String? = null,
        extras: Map<String, String> = emptyMap()
    ): Resolution? {
        val candidates = if (packageHint != null) listOf(packageHint)
                         else db.appRegistryDao().packagesByCapability(capability.name)
        if (candidates.isEmpty()) return null
        val pkg = candidates.first()

        // 1. Launch intent for OPEN_APP
        if (capability == AppCapability.OPEN_APP) {
            val i = context.packageManager.getLaunchIntentForPackage(pkg)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ?: return null
            return Resolution(Strategy.LAUNCH_INTENT, pkg, i)
        }

        val link = db.appRegistryDao().linkFor(pkg, capability.name)

        // 2. Deep link
        if (link?.deepLinkPattern != null) {
            val uri = buildDeepLink(link.deepLinkPattern, capability, extras)
            val i   = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return Resolution(Strategy.DEEP_LINK, pkg, i)
        }

        // 3. Intent action (ACTION_SEND, ACTION_DIAL, etc.)
        if (link?.intentAction != null) {
            val i = buildActionIntent(link, extras).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            return Resolution(Strategy.ACTION_INTENT, pkg, i)
        }

        // 4. Stored UI procedure
        val proc = db.appRegistryDao().bestProcedure(pkg, capability.name)
        if (proc != null) return Resolution(Strategy.UI_PROCEDURE, pkg, procedure = proc)

        return null
    }

    // ── Procedure outcome tracking ─────────────────────────────────────────────

    fun recordProcedureSuccess(db: AegisDatabase, procedureId: Long) {
        db.appRegistryDao().recordSuccess(procedureId, System.currentTimeMillis())
    }

    fun recordProcedureFailure(db: AegisDatabase, procedureId: Long) {
        db.appRegistryDao().recordFailure(procedureId, System.currentTimeMillis())
    }

    // ── Label → package lookup ─────────────────────────────────────────────────

    fun packageForLabel(db: AegisDatabase, label: String): String? {
        val lower = label.lowercase()
        return db.appRegistryDao().allRecords()
            .firstOrNull { it.label.lowercase() == lower || it.packageName.contains(lower) }
            ?.packageName
    }

    // ── Capability query helpers ───────────────────────────────────────────────

    fun capabilitiesFor(db: AegisDatabase, packageName: String): List<AppCapability> =
        db.appRegistryDao().capabilitiesForPackage(packageName)
            .mapNotNull { runCatching { AppCapability.valueOf(it) }.getOrNull() }

    fun packagesFor(db: AegisDatabase, capability: AppCapability): List<String> =
        db.appRegistryDao().packagesByCapability(capability.name)

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildDeepLink(pattern: String, capability: AppCapability, extras: Map<String, String>): String {
        val text    = extras["text"]    ?: ""
        val contact = extras["contact"] ?: ""
        val query   = extras["query"]   ?: text
        return when {
            pattern.startsWith("whatsapp://") -> "whatsapp://send?text=${Uri.encode(text)}"
            pattern.startsWith("tg://")       -> "tg://msg?text=${Uri.encode(text)}"
            pattern.startsWith("spotify:")    -> if (query.isNotBlank()) "spotify:search:${Uri.encode(query)}" else "spotify:"
            pattern.startsWith("youtube://")  -> if (query.isNotBlank()) "youtube://search?query=${Uri.encode(query)}" else "youtube://"
            pattern.startsWith("geo:")        -> "geo:0,0?q=${Uri.encode(query)}"
            else                              -> pattern
        }
    }

    private fun buildActionIntent(link: com.newax.aegis.db.entity.AppCapabilityLink, extras: Map<String, String>): Intent {
        val i = Intent(link.intentAction)
        i.setPackage(link.packageName)
        when (link.intentAction) {
            Intent.ACTION_SEND -> {
                val mime = link.mimeTypes?.split(",")?.firstOrNull() ?: "text/plain"
                i.type = mime
                if (mime == "text/plain") i.putExtra(Intent.EXTRA_TEXT, extras["text"] ?: "")
                else extras["uri"]?.let { i.putExtra(Intent.EXTRA_STREAM, Uri.parse(it)) }
            }
            Intent.ACTION_DIAL -> {
                extras["phone"]?.let { i.data = Uri.parse("tel:$it") }
            }
            Intent.ACTION_VIEW -> {
                extras["url"]?.let { i.data = Uri.parse(it) }
            }
        }
        return i
    }
}
