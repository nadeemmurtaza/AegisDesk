package com.newax.aegis.engine.learning

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** Data sources the learner scans, in round-robin order. */
enum class ScanSource(val label: String, val batchSize: Int) {
    CONTACTS("Contacts", 15),
    SMS_INBOX("SMS Inbox", 25),
    SMS_SENT("SMS Sent", 25),
    CALL_LOGS("Call Logs", 60),
    GALLERY_OCR("Gallery Images", 3),
    DOWNLOADS("Downloaded Files", 5)
}

/**
 * Encrypted persistent state for the background scan queue.
 * Tracks: which source is next, row offset per source, last-seen timestamp per source,
 * and whether the self-learning engine is enabled at all.
 */
object ScanProgress {
    private val lock = Any()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) = synchronized(lock) {
        if (prefs != null) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            context, "aegis_scan_progress", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Returns true if the self-learning engine is allowed to run. */
    fun isEnabled(): Boolean = prefs?.getBoolean("enabled", false) ?: false

    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean("enabled", enabled)?.apply()
    }

    /** Which source to scan next (round-robin by index). */
    fun currentSource(): ScanSource {
        val idx = prefs?.getInt("source_idx", 0) ?: 0
        return ScanSource.entries[idx % ScanSource.entries.size]
    }

    /** Move to the next source in the cycle. */
    fun advanceSource() {
        val next = ((prefs?.getInt("source_idx", 0) ?: 0) + 1) % ScanSource.entries.size
        prefs?.edit()?.putInt("source_idx", next)?.apply()
    }

    /** Row offset within the current source (for cursor-based paging). */
    fun getOffset(source: ScanSource): Int = prefs?.getInt("off_${source.name}", 0) ?: 0

    fun setOffset(source: ScanSource, offset: Int) {
        prefs?.edit()?.putInt("off_${source.name}", offset)?.apply()
    }

    /** Timestamp of the most-recent item processed for this source. Skip items older than this. */
    fun getLastSeenMs(source: ScanSource): Long = prefs?.getLong("ts_${source.name}", 0L) ?: 0L

    fun setLastSeenMs(source: ScanSource, ms: Long) {
        prefs?.edit()?.putLong("ts_${source.name}", ms)?.apply()
    }

    /** Reset all offsets and timestamps — triggers full re-scan from the beginning. */
    fun resetAll() {
        val editor = prefs?.edit() ?: return
        editor.putInt("source_idx", 0)
        ScanSource.entries.forEach { s ->
            editor.putInt("off_${s.name}", 0)
            editor.putLong("ts_${s.name}", 0L)
        }
        editor.apply()
    }

    /** Timestamp (ms) of the last completed scan batch. 0 if never run. */
    fun getLastRunMs(): Long = prefs?.getLong("last_run_ms", 0L) ?: 0L

    fun setLastRunMs(ms: Long) {
        prefs?.edit()?.putLong("last_run_ms", ms)?.apply()
    }

    /** Scan interval in minutes. Default 20. */
    fun getIntervalMinutes(): Long = prefs?.getLong("interval_min", 20L) ?: 20L

    fun setIntervalMinutes(min: Long) {
        prefs?.edit()?.putLong("interval_min", min)?.apply()
    }

    /** Whether a specific source is enabled (defaults to true for all). */
    fun isSourceEnabled(source: ScanSource): Boolean =
        prefs?.getBoolean("src_${source.name}", true) ?: true

    fun setSourceEnabled(source: ScanSource, enabled: Boolean) {
        prefs?.edit()?.putBoolean("src_${source.name}", enabled)?.apply()
    }

    /** Total drafts created across all runs (lifetime counter). */
    fun getTotalDraftsCreated(): Int = prefs?.getInt("total_drafts", 0) ?: 0

    fun addToDraftsCreated(count: Int) {
        val cur = getTotalDraftsCreated()
        prefs?.edit()?.putInt("total_drafts", cur + count)?.apply()
    }

    /** Serialize all prefs to JSON for backup. */
    fun exportToJson(): JSONObject {
        val j = JSONObject()
        j.put("enabled",       isEnabled())
        j.put("source_idx",    prefs?.getInt("source_idx", 0) ?: 0)
        j.put("interval_min",  getIntervalMinutes())
        j.put("last_run_ms",   getLastRunMs())
        j.put("total_drafts",  getTotalDraftsCreated())
        ScanSource.entries.forEach { s ->
            j.put("off_${s.name}",  getOffset(s))
            j.put("ts_${s.name}",   getLastSeenMs(s))
            j.put("src_${s.name}",  isSourceEnabled(s))
        }
        return j
    }

    /** Restore all prefs from a backup JSON object. */
    fun importFromJson(j: JSONObject) = synchronized(lock) {
        val editor = prefs?.edit() ?: return
        j.keys().forEach { k ->
            when (val v = j.get(k)) {
                is Boolean -> editor.putBoolean(k, v)
                is Int     -> editor.putInt(k, v)
                is Long    -> editor.putLong(k, v)
                is Double  -> {
                    // JSON has no int/long distinction — infer from key name
                    if (k == "last_run_ms" || k == "interval_min" || k.startsWith("ts_"))
                        editor.putLong(k, v.toLong())
                    else if (k == "source_idx" || k.startsWith("off_") || k == "total_drafts")
                        editor.putInt(k, v.toInt())
                    else
                        editor.putLong(k, v.toLong())
                }
                else -> { /* skip unknown */ }
            }
        }
        editor.apply()
    }

    /** Human-readable status line for the settings UI. */
    fun statusSummary(): String {
        val src = currentSource()
        return "Next: ${src.label} (offset ${getOffset(src)})"
    }
}
