package com.newax.aegis.engine

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Email as ContactEmail
import android.util.Log
import com.newax.aegis.memory.EncryptedMemory

/**
 * Full contacts management engine:
 *  - Scan all contacts, normalize names (transliterate, fix spelling, expand abbreviations)
 *  - Remove duplicate phone numbers within a single contact
 *  - Detect contacts that represent the same person (shared phone numbers → merge)
 *  - Safely add discovered phone/email/address from OCR/screen without overwriting
 *  - Build PersonIntelligence profiles for all contacts
 *
 * All write operations are logged and reversible via a dry-run mode.
 */
class ContactsManager(private val context: Context, private val memory: EncryptedMemory) {

    private val cr = context.contentResolver
    private val TAG = "NewaxContacts"

    data class ContactSummary(
        val contactId: String,
        val rawContactId: String,
        val displayName: String,
        val phones: List<PhoneEntry>,
        val emails: List<String>,
        val organization: String?
    )

    data class PhoneEntry(val dataId: String, val number: String, val type: Int)

    data class DuplicateGroup(
        val sharedPhone: String,
        val contacts: List<ContactSummary>
    )

    data class NormalizationReport(
        val contactId: String,
        val oldName: String,
        val newName: String,
        val reason: String
    )

    data class ScanReport(
        val totalContacts: Int,
        val renamedCount: Int,
        val deduplicatedPhones: Int,
        val mergedContacts: Int,
        val duplicateGroups: List<DuplicateGroup>,
        val normalizationReports: List<NormalizationReport>
    )

    // ── Contact reading ──────────────────────────────────────────────────────────

    fun loadAllContacts(): List<ContactSummary> {
        val result = mutableListOf<ContactSummary>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        cr.query(
            ContactsContract.Contacts.CONTENT_URI, projection,
            null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val rawId = getRawContactId(id) ?: continue
                result += ContactSummary(
                    contactId = id,
                    rawContactId = rawId,
                    displayName = name,
                    phones = phonesForContact(id),
                    emails = emailsForContact(id),
                    organization = organizationForContact(id)
                )
            }
        }
        return result
    }

    private fun getRawContactId(contactId: String): String? =
        cr.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun phonesForContact(contactId: String): List<PhoneEntry> {
        val result = mutableListOf<PhoneEntry>()
        cr.query(
            Phone.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, Phone.NUMBER, Phone.TYPE),
            "${Phone.CONTACT_ID} = ?", arrayOf(contactId), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val dataId = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                val type   = cursor.getInt(2)
                result += PhoneEntry(dataId, number, type)
            }
        }
        return result
    }

    private fun emailsForContact(contactId: String): List<String> {
        val result = mutableListOf<String>()
        cr.query(
            ContactEmail.CONTENT_URI,
            arrayOf(ContactEmail.ADDRESS),
            "${ContactEmail.CONTACT_ID} = ?", arrayOf(contactId), null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { result += it }
        }
        return result
    }

    private fun organizationForContact(contactId: String): String? =
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(CommonDataKinds.Organization.COMPANY),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, CommonDataKinds.Organization.CONTENT_ITEM_TYPE), null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    // ── Full scan & cleanup ──────────────────────────────────────────────────────

    /**
     * Full scan: normalize names, dedup phones, detect merges.
     * @param dryRun if true, only report — don't write to contacts DB.
     * @param autoMerge if true, auto-merges confirmed duplicates (same phone → same person).
     */
    fun scanAndClean(dryRun: Boolean = false, autoMerge: Boolean = false): ScanReport {
        val allContacts = loadAllContacts()
        val renames = mutableListOf<NormalizationReport>()
        var dedupCount = 0

        for (contact in allContacts) {
            // 1. Normalize name
            val norm = ContactNormalizer.normalize(contact.displayName)
            if (norm.changed) {
                renames += NormalizationReport(contact.contactId, contact.displayName, norm.name, norm.reason)
                if (!dryRun) updateContactName(contact.rawContactId, norm.name)
                Log.d(TAG, "Renamed: '${contact.displayName}' → '${norm.name}' (${norm.reason})")
            }

            // 2. Remove duplicate phone numbers within this contact
            val dupsRemoved = if (!dryRun) deduplicatePhones(contact) else countDuplicatePhones(contact)
            dedupCount += dupsRemoved
        }

        // 3. Detect contacts that share a phone number (different contacts, same person)
        val duplicateGroups = findDuplicateContactGroups(allContacts)
        var mergedCount = 0
        if (autoMerge && !dryRun) {
            for (group in duplicateGroups) {
                if (group.contacts.size == 2) {
                    val ok = mergeContacts(group.contacts[0].rawContactId, group.contacts[1].rawContactId)
                    if (ok) { mergedCount++; Log.d(TAG, "Merged: ${group.contacts[0].displayName} + ${group.contacts[1].displayName}") }
                }
            }
        }

        return ScanReport(
            totalContacts = allContacts.size,
            renamedCount = renames.size,
            deduplicatedPhones = dedupCount,
            mergedContacts = mergedCount,
            duplicateGroups = duplicateGroups,
            normalizationReports = renames
        )
    }

    // ── Name update ──────────────────────────────────────────────────────────────

    private fun updateContactName(rawContactId: String, newName: String) {
        try {
            val ops = arrayListOf(
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(rawContactId, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    )
                    .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, newName)
                    .build()
            )
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update name for rawId=$rawContactId: ${e.message}")
        }
    }

    // ── Phone deduplication ──────────────────────────────────────────────────────

    private fun deduplicatePhones(contact: ContactSummary): Int {
        val seen = mutableSetOf<String>()
        val toDelete = mutableListOf<String>()
        for (phone in contact.phones) {
            val normalized = ContactNormalizer.normalizePhone(phone.number)
            if (!seen.add(normalized)) {
                toDelete += phone.dataId
                Log.d(TAG, "Duplicate phone ${phone.number} in '${contact.displayName}' → removing dataId=${phone.dataId}")
            }
        }
        toDelete.forEach { dataId ->
            try {
                cr.delete(
                    ContactsContract.Data.CONTENT_URI,
                    "${ContactsContract.Data._ID} = ?",
                    arrayOf(dataId)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete phone dataId=$dataId: ${e.message}")
            }
        }
        return toDelete.size
    }

    private fun countDuplicatePhones(contact: ContactSummary): Int {
        val seen = mutableSetOf<String>()
        return contact.phones.count { phone -> !seen.add(ContactNormalizer.normalizePhone(phone.number)) }
    }

    // ── Duplicate contact detection ──────────────────────────────────────────────

    fun findDuplicateContactGroups(contacts: List<ContactSummary>): List<DuplicateGroup> {
        val phoneToContacts = mutableMapOf<String, MutableList<ContactSummary>>()
        for (contact in contacts) {
            for (phone in contact.phones) {
                val norm = ContactNormalizer.normalizePhone(phone.number)
                phoneToContacts.getOrPut(norm) { mutableListOf() } += contact
            }
        }
        return phoneToContacts.entries
            .filter { it.value.size >= 2 }
            .map { (phone, group) -> DuplicateGroup(phone, group.distinctBy { it.contactId }) }
    }

    // ── Merge contacts ───────────────────────────────────────────────────────────

    /**
     * Merges two raw contacts using Android's AggregationExceptions.
     * Both rawContactIds must be from the same account or "phone" account.
     */
    fun mergeContacts(rawContactId1: String, rawContactId2: String): Boolean {
        return try {
            val ops = arrayListOf(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE,
                        ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1,
                        rawContactId1.toLong())
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2,
                        rawContactId2.toLong())
                    .build()
            )
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed: ${e.message}")
            false
        }
    }

    // ── Save discovered info from OCR/screen ─────────────────────────────────────

    /**
     * Adds a phone number to a contact if it doesn't already exist there.
     * Safe: checks for duplicates before writing.
     */
    fun saveDiscoveredPhone(contactId: String, phone: String, type: Int = Phone.TYPE_MOBILE): Boolean {
        val existing = phonesForContact(contactId)
        val alreadyThere = existing.any { ContactNormalizer.phonesAreEqual(it.number, phone) }
        if (alreadyThere) return false

        val rawId = getRawContactId(contactId) ?: return false
        return try {
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId.toLong())
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, phone)
                    .withValue(Phone.TYPE, type)
                    .build()
            )
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d(TAG, "Added phone $phone to contactId=$contactId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveDiscoveredPhone failed: ${e.message}")
            false
        }
    }

    /**
     * Adds an email address to a contact if it doesn't already exist.
     */
    fun saveDiscoveredEmail(contactId: String, email: String, type: Int = ContactEmail.TYPE_OTHER): Boolean {
        val existing = emailsForContact(contactId)
        if (existing.any { it.equals(email, ignoreCase = true) }) return false

        val rawId = getRawContactId(contactId) ?: return false
        return try {
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId.toLong())
                    .withValue(ContactsContract.Data.MIMETYPE, ContactEmail.CONTENT_ITEM_TYPE)
                    .withValue(ContactEmail.ADDRESS, email)
                    .withValue(ContactEmail.TYPE, type)
                    .build()
            )
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d(TAG, "Added email $email to contactId=$contactId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveDiscoveredEmail failed: ${e.message}")
            false
        }
    }

    /**
     * Adds a postal address to a contact if the formatted address isn't already saved.
     */
    fun saveDiscoveredAddress(contactId: String, address: String, type: Int = CommonDataKinds.StructuredPostal.TYPE_HOME): Boolean {
        val rawId = getRawContactId(contactId) ?: return false
        // Check for existing — query structured postal for this contact
        val alreadyExists = cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE), null
        )?.use { cursor ->
            (0 until cursor.count).any { i ->
                cursor.moveToPosition(i)
                cursor.getString(0)?.equals(address, ignoreCase = true) == true
            }
        } == true
        if (alreadyExists) return false

        return try {
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId.toLong())
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, address)
                    .withValue(CommonDataKinds.StructuredPostal.TYPE, type)
                    .build()
            )
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d(TAG, "Added address to contactId=$contactId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveDiscoveredAddress failed: ${e.message}")
            false
        }
    }

    /**
     * Called when OCR or accessibility sees a phone/email/address in context of a person.
     * Resolves the contact by name, then saves the info only if the contact is confirmed.
     *
     * @param personName display name from OCR/screen
     * @param infoType "phone", "email", or "address"
     * @param value the raw value discovered
     * @return true if saved to contacts
     */
    fun saveDiscoveredInfo(personName: String, infoType: String, value: String): Boolean {
        val contacts = loadAllContacts()
        val match = contacts.firstOrNull { c ->
            c.displayName.equals(personName, ignoreCase = true) ||
            ContactNormalizer.normalize(c.displayName).name.equals(
                ContactNormalizer.normalize(personName).name, ignoreCase = true)
        } ?: run {
            Log.d(TAG, "saveDiscoveredInfo: no contact match for '$personName'")
            return false
        }

        return when (infoType.lowercase()) {
            "phone"   -> saveDiscoveredPhone(match.contactId, value)
            "email"   -> saveDiscoveredEmail(match.contactId, value)
            "address" -> saveDiscoveredAddress(match.contactId, value)
            else -> false
        }
    }

    // ── Person intelligence pipeline ─────────────────────────────────────────────

    /**
     * Builds or refreshes the PersonIntelligenceProfile for one contact.
     * Reads SMS history + CommunicationLog, extracts personality/tone/style.
     */
    fun buildPersonProfile(contactId: String): PersonIntelligence.PersonIntelligenceProfile? {
        val contact = loadAllContacts().firstOrNull { it.contactId == contactId } ?: return null
        val intelligence = PersonIntelligence(context, memory)
        return intelligence.buildProfile(
            contactId    = contact.contactId,
            displayName  = contact.displayName,
            phoneNumbers = contact.phones.map { it.number },
            emails       = contact.emails
        )
    }

    /** Builds profiles for all contacts — can be run in a WorkManager task. */
    fun buildAllPersonProfiles(onProgress: ((Int, Int) -> Unit)? = null) {
        val all = loadAllContacts()
        val intelligence = PersonIntelligence(context, memory)
        all.forEachIndexed { index, contact ->
            onProgress?.invoke(index + 1, all.size)
            try {
                intelligence.buildProfile(
                    contactId    = contact.contactId,
                    displayName  = contact.displayName,
                    phoneNumbers = contact.phones.map { it.number },
                    emails       = contact.emails
                )
            } catch (e: Exception) {
                Log.w(TAG, "Profile build failed for ${contact.displayName}: ${e.message}")
            }
        }
    }

    fun getPersonProfile(contactId: String): PersonIntelligence.PersonIntelligenceProfile? {
        val intelligence = PersonIntelligence(context, memory)
        return intelligence.loadProfile(contactId)
    }

    fun getPersonProfileByName(name: String): PersonIntelligence.PersonIntelligenceProfile? {
        val contacts = loadAllContacts()
        val match = contacts.firstOrNull { c ->
            c.displayName.equals(name, ignoreCase = true) ||
            ContactNormalizer.normalize(c.displayName).name.equals(
                ContactNormalizer.normalize(name).name, ignoreCase = true)
        } ?: return null
        return getPersonProfile(match.contactId)
    }

    /** Formatted report of the scan for display to the user. */
    fun formatScanReport(report: ScanReport): String = buildString {
        appendLine("Contact scan complete.")
        appendLine("Total contacts: ${report.totalContacts}")
        if (report.renamedCount > 0) appendLine("Renamed: ${report.renamedCount} contacts")
        if (report.deduplicatedPhones > 0) appendLine("Removed duplicate phones: ${report.deduplicatedPhones}")
        if (report.mergedContacts > 0) appendLine("Merged duplicate contacts: ${report.mergedContacts}")
        if (report.duplicateGroups.isNotEmpty()) {
            appendLine("\nPotential duplicates (same phone, different contacts):")
            report.duplicateGroups.take(10).forEach { group ->
                appendLine("  • ${group.contacts.joinToString(" / ") { it.displayName }} [${group.sharedPhone}]")
            }
        }
        if (report.normalizationReports.isNotEmpty()) {
            appendLine("\nRenames applied:")
            report.normalizationReports.take(15).forEach { r ->
                appendLine("  • '${r.oldName}' → '${r.newName}' (${r.reason})")
            }
        }
    }.trim()

    companion object {
        fun phoneByName(context: Context, name: String): String? = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }

        fun emailByName(context: Context, name: String): String? = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }
    }
}
