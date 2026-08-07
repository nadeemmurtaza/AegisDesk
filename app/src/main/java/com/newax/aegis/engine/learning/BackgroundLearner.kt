package com.newax.aegis.engine.learning

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.ContactNormalizer
import com.newax.aegis.engine.ContactsManager
import com.newax.aegis.engine.SensitiveInfoDetector
import com.newax.aegis.memory.EncryptedMemory
import com.newax.aegis.vision.OcrEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs one scan batch for the currently scheduled source.
 * Called from LearningWorker (background thread). Each run processes one small
 * slice of one data source, advances the position, and saves drafts to DraftStore.
 *
 * v2 improvements:
 * - Subject name extracted from source context and passed to FactExtractor
 * - PersonFactStore receives a mention record for every named person encountered
 * - After each batch, PersonFactStore.getPeopleNeedingProfileBuild() is checked
 *   and ContactsManager.buildPersonProfile() is triggered automatically
 * - Disabled sources skipped with loop guard
 */
object BackgroundLearner {

    private const val TAG = "AegisLearner"

    fun runNextBatch(context: Context, memory: EncryptedMemory): Int {
        ScanProgress.init(context)
        if (!ScanProgress.isEnabled()) return 0

        val db = AegisDatabase.get

        var source = ScanProgress.currentSource()
        var skips = 0
        while (!ScanProgress.isSourceEnabled(source) && skips < ScanSource.entries.size) {
            ScanProgress.advanceSource(); source = ScanProgress.currentSource(); skips++
        }
        if (!ScanProgress.isSourceEnabled(source)) {
            Log.d(TAG, "All sources disabled"); return 0
        }

        Log.d(TAG, "Scanning: ${source.label}")

        val drafts = try {
            when (source) {
                ScanSource.CONTACTS    -> scanContacts(context, db, memory)
                ScanSource.SMS_INBOX   -> scanSms(context, Telephony.Sms.Inbox.CONTENT_URI, source, db, memory)
                ScanSource.SMS_SENT    -> scanSms(context, Telephony.Sms.Sent.CONTENT_URI, source, db, memory)
                ScanSource.CALL_LOGS   -> scanCallLogs(context, source, db, memory)
                ScanSource.GALLERY_OCR -> scanGallery(context, source)
                ScanSource.DOWNLOADS   -> scanDownloads(context, source)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied for ${source.label}: ${e.message}"); emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Scan error [${source.label}]: ${e.message}"); emptyList()
        }

        if (drafts.isNotEmpty()) {
            DraftStore.addDrafts(db, drafts)
            ScanProgress.addToDraftsCreated(drafts.size)

            // Record person mentions in PersonFactStore
            drafts.forEach { draft ->
                draft.subjectName?.takeIf { it.isNotBlank() }?.let { name ->
                    PersonFactStore.recordMention(db, name, source.name)
                }
            }
            Log.d(TAG, "${drafts.size} new drafts from ${source.label}")
        }

        // Trigger auto profile builds for people who crossed the importance threshold
        triggerProfileBuilds(context, db, memory)

        ScanProgress.setLastRunMs(System.currentTimeMillis())
        ScanProgress.advanceSource()
        return drafts.size
    }

    // ── Profile build trigger ─────────────────────────────────────────────────

    private fun triggerProfileBuilds(context: Context, db: AegisDatabase, memory: EncryptedMemory) {
        val tooBuild = PersonFactStore.getPeopleNeedingProfileBuild(db)
        if (tooBuild.isEmpty()) return
        val mgr = ContactsManager(context, memory)
        val allContacts = try { mgr.loadAllContacts() } catch (_: Exception) { return }

        tooBuild.forEach { name ->
            val contact = allContacts.firstOrNull { c ->
                c.displayName.equals(name, ignoreCase = true) ||
                c.displayName.contains(name, ignoreCase = true)
            }
            if (contact != null) {
                try {
                    mgr.buildPersonProfile(contact.contactId)
                    PersonFactStore.markProfileBuilt(db, name)
                    Log.d(TAG, "Auto-built profile: $name")
                } catch (e: Exception) {
                    Log.w(TAG, "Profile build failed for $name: ${e.message}")
                }
            } else {
                // Person not in contacts — mark built to avoid retrying every cycle
                PersonFactStore.markProfileBuilt(db, name)
            }
        }
    }

    // ── Contacts ─────────────────────────────────────────────────────────────

    private fun scanContacts(context: Context, db: AegisDatabase, memory: EncryptedMemory): List<LearningDraft> {
        val mgr    = ContactsManager(context, memory)
        val all    = mgr.loadAllContacts()
        val offset = ScanProgress.getOffset(ScanSource.CONTACTS)
        val batch  = all.drop(offset).take(ScanSource.CONTACTS.batchSize)

        val drafts = mutableListOf<LearningDraft>()
        for (contact in batch) {
            if (contact.organization?.isNotBlank() == true || contact.emails.isNotEmpty()) {
                FactExtractor.extractFromContact(
                    contact.displayName, contact.organization,
                    contact.phones.map { it.number }, contact.emails
                ).forEach { f ->
                    drafts += toDraft(f, "Contact: ${contact.displayName}", "", contact.displayName)
                }
            }
            // Record contact mention in PersonFactStore
            PersonFactStore.recordMention(db, contact.displayName, ScanSource.CONTACTS.name)
        }

        val nextOffset = if (batch.size < ScanSource.CONTACTS.batchSize) 0 else offset + batch.size
        ScanProgress.setOffset(ScanSource.CONTACTS, nextOffset)
        return drafts
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    private fun scanSms(
        context: Context,
        uri: android.net.Uri,
        source: ScanSource,
        db: AegisDatabase,
        memory: EncryptedMemory
    ): List<LearningDraft> {
        val lastMs = ScanProgress.getLastSeenMs(source)
        val drafts = mutableListOf<LearningDraft>()
        var newestMs = lastMs

        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(lastMs.toString()),
            "${Telephony.Sms.DATE} ASC LIMIT ${source.batchSize}"
        )?.use { cursor ->
            val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val addr   = cursor.getString(addrIdx) ?: "Unknown"
                val body   = cursor.getString(bodyIdx) ?: continue
                val dateMs = cursor.getLong(dateIdx)
                if (dateMs > newestMs) newestMs = dateMs

                val label      = if (source == ScanSource.SMS_SENT) "SMS to $addr" else "SMS from $addr"
                val subjectName = resolveSubject(addr)

                // Record mention even if no facts are extracted
                if (subjectName != null) {
                    PersonFactStore.recordMention(db, subjectName, source.name)
                }

                val facts = FactExtractor.extract(body, label, subjectName)
                if (facts.isNotEmpty()) {
                    val snippet = SensitiveInfoDetector.analyze(body.take(80)).redactedText
                    facts.forEach { f -> drafts += toDraft(f, label, snippet, subjectName) }
                }
            }
        }

        if (newestMs > lastMs) ScanProgress.setLastSeenMs(source, newestMs)
        return drafts
    }

    // ── Call logs ────────────────────────────────────────────────────────────

    private fun scanCallLogs(
        context: Context,
        source: ScanSource,
        db: AegisDatabase,
        memory: EncryptedMemory
    ): List<LearningDraft> {
        val lastMs = ScanProgress.getLastSeenMs(source)
        val drafts = mutableListOf<LearningDraft>()
        val callCounts = mutableMapOf<String, Int>()
        var newestMs = lastMs

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            "${CallLog.Calls.DATE} > ?", arrayOf(lastMs.toString()),
            "${CallLog.Calls.DATE} ASC LIMIT ${source.batchSize}"
        )?.use { cursor ->
            val numIdx  = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durIdx  = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numIdx) ?: continue
                val name   = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }
                    ?: ContactNormalizer.normalizePhone(number)
                val dateMs = cursor.getLong(dateIdx)
                val durSec = cursor.getLong(durIdx)
                val type   = cursor.getInt(typeIdx)
                if (dateMs > newestMs) newestMs = dateMs

                val typeLabel = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE   -> "missed"
                    else -> "call"
                }
                callCounts[name] = (callCounts[name] ?: 0) + 1
                PersonFactStore.recordMention(db, name, source.name)

                if (durSec > 300) {
                    FactExtractor.extractFromCall(name, typeLabel, durSec, false)
                        .forEach { f -> drafts += toDraft(f, "Call Log", "$typeLabel, ${durSec / 60} min", name) }
                }
            }
        }

        callCounts.filter { it.value >= 3 }.forEach { (name, count) ->
            FactExtractor.extractFromCall(name, "call", 0, true).forEach { f ->
                drafts += toDraft(
                    f.copy(fact = "${f.fact} ($count calls in scan)"),
                    "Call Log", "frequency: $count", name
                )
            }
        }

        if (newestMs > lastMs) ScanProgress.setLastSeenMs(source, newestMs)
        return drafts
    }

    // ── Gallery OCR ──────────────────────────────────────────────────────────

    private fun scanGallery(context: Context, source: ScanSource): List<LearningDraft> {
        val offset = ScanProgress.getOffset(source)
        val drafts = mutableListOf<LearningDraft>()

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME),
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT ${source.batchSize} OFFSET $offset"
        )?.use { cursor ->
            val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: File(path).name
                val file = File(path)
                if (!file.exists() || file.length() > 8 * 1024 * 1024) continue

                val bitmap = try { BitmapFactory.decodeFile(path) ?: continue }
                catch (_: Exception) { continue }

                var ocrText: String? = null
                val latch = CountDownLatch(1)
                OcrEngine.init()
                OcrEngine.analyze(bitmap, "Gallery") { result ->
                    ocrText = result?.safeText; latch.countDown()
                }
                latch.await(12, TimeUnit.SECONDS)
                bitmap.recycle()

                val text = ocrText?.takeIf { it.length >= 15 } ?: continue
                FactExtractor.extract(text, "Gallery: $name").forEach { f ->
                    drafts += toDraft(f, "Gallery OCR: $name", text.take(60))
                }
            }
        }

        ScanProgress.setOffset(source, offset + source.batchSize)
        return drafts
    }

    // ── Downloads ────────────────────────────────────────────────────────────

    private fun scanDownloads(context: Context, source: ScanSource): List<LearningDraft> {
        val offset = ScanProgress.getOffset(source)
        val drafts = mutableListOf<LearningDraft>()

        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATA,
                    MediaStore.Downloads.MIME_TYPE),
                null, null,
                "${MediaStore.Downloads.DATE_ADDED} DESC LIMIT ${source.batchSize} OFFSET $offset"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                val dataIdx = cursor.getColumnIndex(MediaStore.Downloads.DATA)
                val mimeIdx = cursor.getColumnIndex(MediaStore.Downloads.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val path = cursor.getString(dataIdx) ?: continue
                    val mime = cursor.getString(mimeIdx) ?: ""
                    when {
                        mime.startsWith("text/") -> {
                            val text = try { File(path).readText(Charsets.UTF_8).take(3000) }
                            catch (_: Exception) { continue }
                            FactExtractor.extract(text, "File: $name").forEach { f ->
                                drafts += toDraft(f, "Downloads: $name", text.take(60))
                            }
                        }
                        mime.contains("pdf") -> {
                            drafts += toDraft(
                                FactExtractor.ExtractedFact("personal", "PDF file in downloads: $name", 0.45f),
                                "Downloads", name
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Downloads scan error: ${e.message}") }

        ScanProgress.setOffset(source, offset + source.batchSize)
        return drafts
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolve a phone address to a human name if it looks like a name already.
     * Phone numbers return null (no name to link).
     */
    private fun resolveSubject(addr: String): String? {
        if (addr.isBlank()) return null
        val digits = addr.count { it.isDigit() }
        // If majority digits → it's a phone number, not a name
        return if (digits > addr.length / 2) null
        else addr.trim().takeIf { it.length >= 2 }
    }

    private fun toDraft(
        fact: FactExtractor.ExtractedFact,
        source: String,
        snippet: String,
        subjectName: String? = null
    ) = LearningDraft(
        category      = fact.category,
        fact          = fact.fact,
        source        = source,
        sourceSnippet = snippet,
        confidence    = fact.confidence,
        timestampMs   = System.currentTimeMillis(),
        subjectName   = subjectName ?: fact.subjectName
    )
}
