package com.newax.aegis.engine.dev.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.engine.HabitTracker
import com.newax.aegis.engine.apps.AppCapability
import com.newax.aegis.engine.apps.AppIntelligence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val targetSdk: Int,
    val installedMs: Long,
    val lastUpdatedMs: Long,
    val permissions: List<String>,
    val capabilities: List<String>,
    val openCount: Int,
    val peakHour: Int,
    val lastOpenMs: Long
)

data class AppDeepLinks(
    val packageName: String,
    val schemes: List<String>,
    val hosts: List<String>,
    val activities: List<String>
)

data class VersionChangeReport(
    val packageName: String,
    val oldVersion: String,
    val newVersion: String,
    val changedMs: Long
)

object AppInspector {

    private val versionCache = mutableMapOf<String, Pair<String, Long>>()

    suspend fun listInstalled(context: Context, db: NewaxDatabase, includeSystem: Boolean = false): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            packages
                .filter { pkg -> includeSystem || ((pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { pkg ->
                    val appInfo = pkg.applicationInfo
                    val habits = HabitTracker.getPatternForPackage(pkg.packageName)
                    val perms = pkg.requestedPermissions?.toList() ?: emptyList()
                    val caps = runCatching { AppIntelligence.capabilitiesFor(db, pkg.packageName).map { it.name } }.getOrDefault(emptyList())
                    val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pkg.packageName
                    InstalledApp(
                        packageName = pkg.packageName,
                        label = label,
                        versionName = pkg.versionName ?: "",
                        versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong(),
                        isSystem = ((appInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0,
                        targetSdk = appInfo?.targetSdkVersion ?: 0,
                        installedMs = pkg.firstInstallTime,
                        lastUpdatedMs = pkg.lastUpdateTime,
                        permissions = perms.take(10),
                        capabilities = caps,
                        openCount = habits?.openCount ?: 0,
                        peakHour = habits?.peakHour ?: -1,
                        lastOpenMs = habits?.lastOpenMs ?: 0L
                    )
                }
                .sortedByDescending { it.openCount }
        }

    suspend fun inspect(packageName: String, context: Context, db: NewaxDatabase): InstalledApp? =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val pkg = runCatching { pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: return@withContext null
            val habits = HabitTracker.getPatternForPackage(packageName)
            val caps = runCatching { AppIntelligence.capabilitiesFor(db, packageName).map { it.name } }.getOrDefault(emptyList())
            val appInfo = pkg.applicationInfo
            val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: packageName
            InstalledApp(
                packageName = packageName,
                label = label,
                versionName = pkg.versionName ?: "",
                versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong(),
                isSystem = ((appInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0,
                targetSdk = appInfo?.targetSdkVersion ?: 0,
                installedMs = pkg.firstInstallTime,
                lastUpdatedMs = pkg.lastUpdateTime,
                permissions = pkg.requestedPermissions?.toList() ?: emptyList(),
                capabilities = caps,
                openCount = habits?.openCount ?: 0,
                peakHour = habits?.peakHour ?: -1,
                lastOpenMs = habits?.lastOpenMs ?: 0L
            )
        }

    suspend fun deepLinks(packageName: String, context: Context): AppDeepLinks = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val schemes = mutableSetOf<String>()
        val hosts = mutableSetOf<String>()
        val activities = mutableListOf<String>()
        runCatching {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_INTENT_FILTERS)
            info.activities?.forEach { act ->
                activities.add(act.name)
                val resolved = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW).setPackage(packageName), 0)
                resolved.forEach { ri ->
                    ri.filter?.schemesIterator()?.forEach { schemes.add(it) }
                    ri.filter?.authoritiesIterator()?.forEach { hosts.add(it.host) }
                }
            }
        }
        AppDeepLinks(packageName, schemes.toList(), hosts.toList(), activities)
    }

    suspend fun learnedProcedures(packageName: String, db: NewaxDatabase): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawDb = db.openHelper.readableDatabase
                val results = mutableListOf<Map<String, String>>()
                val cursor = rawDb.query(
                    "SELECT id, taskCapability, successCount, failureCount FROM ui_procedures WHERE packageName = ? LIMIT 50",
                    arrayOf(packageName)
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        results.add(mapOf(
                            "id" to c.getString(0),
                            "taskCapability" to (c.getString(1) ?: ""),
                            "success" to c.getString(2),
                            "fail" to c.getString(3)
                        ))
                    }
                }
                results
            }.getOrDefault(emptyList())
        }

    fun detectVersionChanges(context: Context): List<VersionChangeReport> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        val changes = mutableListOf<VersionChangeReport>()
        for (pkg in packages) {
            val currentVersion = pkg.versionName ?: continue
            val cached = versionCache[pkg.packageName]
            if (cached != null && cached.first != currentVersion) {
                changes.add(VersionChangeReport(pkg.packageName, cached.first, currentVersion, pkg.lastUpdateTime))
            }
            versionCache[pkg.packageName] = Pair(currentVersion, pkg.lastUpdateTime)
        }
        return changes
    }

    suspend fun capabilitiesSummary(db: NewaxDatabase): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val rawDb = db.openHelper.readableDatabase
        val result = mutableMapOf<String, MutableList<String>>()
        runCatching {
            val cursor = rawDb.query(
                "SELECT packageName, capability FROM app_capability_links GROUP BY packageName, capability LIMIT 500")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val pkg = c.getString(0)
                    val cap = c.getString(1)
                    result.getOrPut(pkg) { mutableListOf() }.add(cap)
                }
            }
        }
        result
    }
}
