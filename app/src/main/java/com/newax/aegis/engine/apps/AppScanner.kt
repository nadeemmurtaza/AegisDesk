package com.newax.aegis.engine.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.AppCapabilityLink
import com.newax.aegis.db.entity.AppRecord
import com.newax.aegis.engine.apps.AppCapability.*
import com.newax.aegis.engine.graph.GraphStore

object AppScanner {

    // ── Phase A+B: package scan + known capability seed ───────────────────────

    fun scan(context: Context, db: AegisDatabase) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val now  = System.currentTimeMillis()
        apps.forEach { info ->
            val pkg     = info.packageName
            val label   = pm.getApplicationLabel(info).toString()
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName ?: "" }.getOrDefault("")
            val launch  = pm.getLaunchIntentForPackage(pkg)?.component?.className
            val existing = db.appRegistryDao().recordByPackage(pkg)
            if (existing != null && existing.version != version) {
                db.appRegistryDao().updateVersion(pkg, version, true, now)
            } else if (existing == null) {
                db.appRegistryDao().upsertRecord(
                    AppRecord(packageName = pkg, label = label, version = version,
                        category = categoryOf(info), launchActivity = launch, lastScanMs = now)
                )
                seedKnownCapabilities(db, pkg)
                seedIntentCapabilities(context, db, pkg)
            }
        }
    }

    // ── Seed graph triples (app --supports--> capability) ─────────────────────

    fun seedGraphTriples(db: AegisDatabase) {
        db.appRegistryDao().allRecords().forEach { record ->
            val caps = db.appRegistryDao().capabilitiesForPackage(record.packageName)
            caps.forEach { cap ->
                GraphStore.saveEdge(db, record.label, "supports", cap.lowercase().replace('_', ' '), "app_scanner")
            }
        }
    }

    // ── Phase C: learn screens from active accessibility window ───────────────

    fun learnScreen(db: AegisDatabase, packageName: String, screenSignature: String,
                    screenType: String, nodesJson: String, appVersion: String) {
        db.appRegistryDao().upsertScreen(
            com.newax.aegis.db.entity.ScreenNode(
                packageName = packageName, screenSignature = screenSignature,
                screenType = screenType, nodes = nodesJson, appVersion = appVersion
            )
        )
    }

    fun learnNavigation(db: AegisDatabase, fromSig: String, toSig: String,
                        viewId: String, contentDesc: String, text: String) {
        db.appRegistryDao().upsertNavEdge(
            com.newax.aegis.db.entity.NavEdge(
                fromSignature = fromSig, toSignature = toSig,
                actionViewId = viewId, actionContentDesc = contentDesc, actionText = text
            )
        )
    }

    // ── Known static capability map ───────────────────────────────────────────

    private val KNOWN_CAPABILITIES: Map<String, Set<AppCapability>> = mapOf(
        "com.whatsapp"                    to setOf(OPEN_APP, SEND_TEXT, SEND_FILE, SHARE_MEDIA, CALL, VIDEO_CALL, OPEN_CONTACT_LINK, MESSAGE_WORKFLOW),
        "org.telegram.messenger"          to setOf(OPEN_APP, SEND_TEXT, SEND_FILE, SHARE_MEDIA, CALL, VIDEO_CALL),
        "org.thoughtcrime.securesms"      to setOf(OPEN_APP, SEND_TEXT, SEND_FILE, CALL, VIDEO_CALL),
        "com.google.android.gm"           to setOf(OPEN_APP, SEND_TEXT, SEND_FILE, SHARE_TEXT, SHARE_MEDIA),
        "com.google.android.apps.messaging" to setOf(OPEN_APP, SEND_TEXT),
        "com.spotify.music"               to setOf(OPEN_APP, PLAY_MEDIA, SEARCH_MUSIC, OPEN_URL),
        "com.google.android.youtube"      to setOf(OPEN_APP, PLAY_MEDIA, SEARCH, OPEN_URL),
        "com.android.chrome"              to setOf(OPEN_APP, OPEN_URL, WEB_SEARCH),
        "com.google.android.apps.maps"    to setOf(OPEN_APP, NAVIGATE, OPEN_URL, SEARCH),
        "com.google.android.camera"       to setOf(OPEN_APP, TAKE_PHOTO, SCAN_DOCUMENT),
        "com.google.android.apps.docs"    to setOf(OPEN_APP, UPLOAD, DOWNLOAD, SHARE_TEXT, SHARE_MEDIA, EDIT, SAVE),
        "com.instagram.android"           to setOf(OPEN_APP, SHARE_MEDIA, OPEN_URL),
        "com.twitter.android"             to setOf(OPEN_APP, SHARE_TEXT, OPEN_URL, WEB_SEARCH),
        "com.facebook.katana"             to setOf(OPEN_APP, SHARE_TEXT, SHARE_MEDIA, OPEN_URL),
        "com.android.dialer"              to setOf(OPEN_APP, CALL),
        "com.google.android.dialer"       to setOf(OPEN_APP, CALL),
        "com.android.contacts"            to setOf(OPEN_APP, SEARCH),
        "com.google.android.contacts"     to setOf(OPEN_APP, SEARCH),
        "com.google.android.calendar"     to setOf(OPEN_APP, CREATE_EVENT),
        "com.google.android.keep"         to setOf(OPEN_APP, CREATE_NOTE, SAVE),
        "com.microsoft.office.word"       to setOf(OPEN_APP, EDIT, SAVE, SEND_FILE, DOWNLOAD),
        "com.microsoft.teams"             to setOf(OPEN_APP, SEND_TEXT, SEND_FILE, CALL, VIDEO_CALL),
        "us.zoom.videomeetings"           to setOf(OPEN_APP, CALL, VIDEO_CALL),
        "com.netflix.mediaclient"         to setOf(OPEN_APP, PLAY_MEDIA, SEARCH),
        "com.amazon.mShop.android.shopping" to setOf(OPEN_APP, SEARCH, OPEN_URL),
        "com.slack"                       to setOf(OPEN_APP, SEND_TEXT, SEND_FILE, CALL),
        "com.linkedin.android"            to setOf(OPEN_APP, OPEN_URL, SEARCH),
        "com.snapchat.android"            to setOf(OPEN_APP, SHARE_MEDIA, SEND_FILE)
    )

    private val DEEP_LINKS: Map<String, String> = mapOf(
        "com.whatsapp"                  to "whatsapp://send",
        "org.telegram.messenger"        to "tg://msg",
        "com.spotify.music"             to "spotify:",
        "com.google.android.youtube"    to "youtube://",
        "com.google.android.apps.maps"  to "geo:0,0",
        "com.android.chrome"            to "googlechrome://navigate",
        "com.slack"                     to "slack://",
        "com.microsoft.teams"           to "msteams:"
    )

    private val INTENT_ACTIONS: Map<AppCapability, String> = mapOf(
        SEND_TEXT    to android.content.Intent.ACTION_SEND,
        SEND_FILE    to android.content.Intent.ACTION_SEND,
        SHARE_TEXT   to android.content.Intent.ACTION_SEND,
        SHARE_MEDIA  to android.content.Intent.ACTION_SEND,
        OPEN_URL     to android.content.Intent.ACTION_VIEW,
        CALL         to android.content.Intent.ACTION_DIAL,
        NAVIGATE     to android.content.Intent.ACTION_VIEW
    )

    private fun seedKnownCapabilities(db: AegisDatabase, pkg: String) {
        KNOWN_CAPABILITIES[pkg]?.forEach { cap ->
            db.appRegistryDao().upsertLink(
                AppCapabilityLink(
                    packageName     = pkg,
                    capability      = cap.name,
                    intentAction    = INTENT_ACTIONS[cap],
                    deepLinkPattern = if (cap == OPEN_URL || cap == NAVIGATE || cap == OPEN_APP) DEEP_LINKS[pkg] else null,
                    confidence      = 90
                )
            )
        }
    }

    private fun seedIntentCapabilities(context: Context, db: AegisDatabase, pkg: String) {
        val pm = context.packageManager
        // Detect share targets via ACTION_SEND resolution
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain" }
        val targets = pm.queryIntentActivities(shareIntent, 0).map { it.activityInfo.packageName }.toSet()
        if (pkg in targets) {
            db.appRegistryDao().upsertLink(AppCapabilityLink(
                packageName = pkg, capability = SHARE_TEXT.name,
                intentAction = android.content.Intent.ACTION_SEND, mimeTypes = "text/plain", confidence = 85
            ))
        }
        val mediaIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "image/*" }
        val mediaTargets = pm.queryIntentActivities(mediaIntent, 0).map { it.activityInfo.packageName }.toSet()
        if (pkg in mediaTargets) {
            db.appRegistryDao().upsertLink(AppCapabilityLink(
                packageName = pkg, capability = SHARE_MEDIA.name,
                intentAction = android.content.Intent.ACTION_SEND, mimeTypes = "image/*", confidence = 85
            ))
        }
    }

    private fun categoryOf(info: ApplicationInfo): String {
        val flags = info.flags
        return when {
            flags and ApplicationInfo.FLAG_SYSTEM != 0 -> "system"
            info.packageName.contains("camera")        -> "camera"
            info.packageName.contains("music")
                || info.packageName.contains("spotify")
                || info.packageName.contains("audio")  -> "media"
            info.packageName.contains("message")
                || info.packageName.contains("chat")
                || info.packageName.contains("whatsapp")
                || info.packageName.contains("telegram") -> "communication"
            info.packageName.contains("mail")
                || info.packageName.contains("gmail")  -> "email"
            info.packageName.contains("map")
                || info.packageName.contains("nav")    -> "navigation"
            info.packageName.contains("browser")
                || info.packageName.contains("chrome") -> "browser"
            else                                        -> "app"
        }
    }
}
