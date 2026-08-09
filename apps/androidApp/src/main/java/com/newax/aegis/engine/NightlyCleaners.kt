package com.newax.aegis.engine

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Nightly housekeeping scans.
 *
 * These workers deliberately do NOT ask the model to emit commands. An earlier version
 * built prompts like "If it's blurry or junk, output 'delete file $path'" with the
 * filename interpolated in — a filename is attacker-controlled text, so that handed
 * whoever named the file the ability to steer a destructive command into the assistant,
 * unattended, every night. Scans now report findings; the user decides what to act on.
 */

/**
 * Reports duplicate and incomplete contacts.
 *
 * Duplicate detection is done here in code rather than by asking the model, because it
 * is an exact-match question with a correct answer and no reason to involve inference.
 */
class ContactScannerWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.i("AegisScanner", "Nightly contact scan started")
        return try {
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            )
            val byName = mutableMapOf<String, MutableList<String>>()
            var noPhone = 0
            var total = 0

            applicationContext.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, projection, null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val phoneIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                while (c.moveToNext()) {
                    total++
                    val name = c.getString(nameIdx)?.trim().orEmpty()
                    if (c.getInt(phoneIdx) <= 0) noPhone++
                    if (name.isNotEmpty()) {
                        byName.getOrPut(name.lowercase()) { mutableListOf() }.add(c.getString(idIdx))
                    }
                }
            }

            val duplicateGroups = byName.values.count { it.size > 1 }
            // A count, not a list: contact names are user data and there is no reason to
            // push them through the model to produce a summary.
            Log.i(
                "AegisScanner",
                "Contact scan: $total contacts, $duplicateGroups duplicate name group(s), $noPhone without a number"
            )
            ContactHygiene.publish(ContactHygiene.Report(total, duplicateGroups, noPhone))
            Result.success()
        } catch (e: Exception) {
            Log.w("AegisScanner", "Contact scan failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }
}

/** Latest contact-hygiene findings, for the UI to surface as a reviewable suggestion. */
object ContactHygiene {
    data class Report(val totalContacts: Int, val duplicateNameGroups: Int, val withoutPhone: Int) {
        val hasFindings: Boolean get() = duplicateNameGroups > 0 || withoutPhone > 0
    }

    @Volatile
    var latest: Report? = null
        private set

    fun publish(report: Report) { latest = report }
}
