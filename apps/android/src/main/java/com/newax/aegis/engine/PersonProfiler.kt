package com.newax.aegis.engine

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Email as ContactEmail
import android.provider.ContactsContract.CommonDataKinds.Photo

/**
 * Builds person profiles by correlating on-screen names/numbers with
 * the device contacts database, CommunicationLog, and KnowledgeGraph.
 *
 * Face recognition from bitmaps requires ML Kit Face Detection, which
 * is provided as an integration stub here — call recognizeFacesInBitmap()
 * with a list of candidates to enable it when the dependency is added.
 */
class PersonProfiler(private val context: Context) {

    data class ContactInfo(
        val id: String,
        val displayName: String,
        val phones: List<String>,
        val emails: List<String>,
        val photoUri: Uri?,
        val organization: String?
    )

    enum class RelationshipType { PERSONAL, BUSINESS, FAMILY, UNKNOWN }
    enum class TrustLevel { HIGH, MEDIUM, LOW, UNKNOWN }

    data class PersonProfile(
        val contact: ContactInfo?,
        val communicationHistory: List<LogEntry>,
        val recentMessageCount: Int,
        val dominantTone: String,
        val relationship: RelationshipType,
        val trustLevel: TrustLevel,
        val knownTopics: List<String>,
        val summary: String
    )

    private val cr: ContentResolver = context.contentResolver

    // --- Contact lookup ---

    fun findContactByName(name: String): ContactInfo? {
        val uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(name)
        )
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI
        )
        return cr.query(uri, projection, null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC")
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val id = cursor.getString(0) ?: return null
                val displayName = cursor.getString(1) ?: name
                val photoUri = cursor.getString(2)?.let { Uri.parse(it) }
                ContactInfo(
                    id = id,
                    displayName = displayName,
                    phones = phonesForContact(id),
                    emails = emailsForContact(id),
                    photoUri = photoUri,
                    organization = organizationForContact(id)
                )
            }
    }

    fun findContactByPhone(phone: String): ContactInfo? {
        val normalized = phone.filter { it.isDigit() || it == '+' }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalized)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI
        )
        return cr.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getString(0) ?: return null
            val displayName = cursor.getString(1) ?: phone
            val photoUri = cursor.getString(2)?.let { Uri.parse(it) }
            ContactInfo(
                id = id,
                displayName = displayName,
                phones = phonesForContact(id),
                emails = emailsForContact(id),
                photoUri = photoUri,
                organization = organizationForContact(id)
            )
        }
    }

    fun findContactByEmail(email: String): ContactInfo? {
        val selection = "${ContactEmail.ADDRESS} = ?"
        val projection = arrayOf(
            ContactEmail.CONTACT_ID,
            ContactEmail.DISPLAY_NAME_PRIMARY,
            ContactEmail.PHOTO_URI
        )
        return cr.query(ContactEmail.CONTENT_URI, projection, selection, arrayOf(email), null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val id = cursor.getString(0) ?: return null
                val displayName = cursor.getString(1) ?: email
                val photoUri = cursor.getString(2)?.let { Uri.parse(it) }
                ContactInfo(
                    id = id,
                    displayName = displayName,
                    phones = phonesForContact(id),
                    emails = emailsForContact(id),
                    photoUri = photoUri,
                    organization = organizationForContact(id)
                )
            }
    }

    // --- Profile building ---

    fun buildProfile(
        identifier: String,                  // name, phone, or email
        commLog: CommunicationLog,
        graph: KnowledgeGraph
    ): PersonProfile {
        val contact = when {
            identifier.contains('@') -> findContactByEmail(identifier)
            identifier.filter { it.isDigit() }.length > 7 -> findContactByPhone(identifier)
            else -> findContactByName(identifier)
        }

        val logEntries = commLog.getLogsForContact(identifier, limit = 20)

        // Tone profile across recent messages
        val tones = logEntries.map { ToneAnalyzer.analyze(it.message) }
        val avgSentiment = when {
            tones.isEmpty() -> "UNKNOWN"
            tones.count { it.sentiment == ToneAnalyzer.Sentiment.POSITIVE } > tones.size / 2 -> "POSITIVE"
            tones.count { it.sentiment == ToneAnalyzer.Sentiment.NEGATIVE } > tones.size / 2 -> "NEGATIVE"
            else -> "NEUTRAL"
        }

        val relationship = inferRelationship(contact, logEntries)
        val trust = inferTrust(contact, logEntries, tones)

        // Topics from knowledge graph: look up edges for this identifier
        val displayName = contact?.displayName ?: ""
        val graphEdges = graph.query(identifier).ifEmpty { graph.query(displayName) }
        val topics = graphEdges.map { "${it.relation}:${it.to}" }.distinct().take(5)

        val summary = buildSummary(contact, logEntries, avgSentiment, relationship, trust, topics)

        return PersonProfile(
            contact = contact,
            communicationHistory = logEntries,
            recentMessageCount = logEntries.size,
            dominantTone = avgSentiment,
            relationship = relationship,
            trustLevel = trust,
            knownTopics = topics,
            summary = summary
        )
    }

    /** Tries to identify a person from text on screen (names, phone, email). */
    fun identifyFromText(text: String, commLog: CommunicationLog, graph: KnowledgeGraph): List<PersonProfile> {
        val entities = ContextCorrelator.extractEntities(text)
        val profiles = mutableListOf<PersonProfile>()
        val seen = mutableSetOf<String>()

        for (name in entities.names.take(3)) {
            if (seen.add(name)) profiles += buildProfile(name, commLog, graph)
        }
        for (phone in entities.phones.take(2)) {
            if (seen.add(phone)) profiles += buildProfile(phone, commLog, graph)
        }
        for (email in entities.emails.take(2)) {
            if (seen.add(email)) profiles += buildProfile(email, commLog, graph)
        }
        return profiles
    }

    /**
     * Face recognition stub. To enable:
     * 1. Add `implementation("com.google.mlkit:face-detection:16.1.7")` to build.gradle.kts
     * 2. Replace body with ML Kit FaceDetection + embedding comparison
     * 3. Load contact photo bitmaps via contactPhotoBytes(contactId)
     */
    fun recognizeFacesInBitmap(
        @Suppress("UNUSED_PARAMETER") bitmap: android.graphics.Bitmap
    ): List<String> {
        // Stub: returns empty list until ML Kit Face Detection is wired
        return emptyList()
    }

    /** Returns the raw photo bytes for a contact (to decode or pass to face matcher). */
    fun contactPhotoBytes(contactId: String): ByteArray? {
        val photoUri = Uri.withAppendedPath(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId.toLongOrNull() ?: return null),
            ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
        )
        return try {
            cr.openInputStream(photoUri)?.use { it.readBytes() }
        } catch (_: Exception) { null }
    }

    // --- Helpers ---

    private fun phonesForContact(contactId: String): List<String> {
        val result = mutableListOf<String>()
        cr.query(
            Phone.CONTENT_URI, arrayOf(Phone.NUMBER),
            "${Phone.CONTACT_ID} = ?", arrayOf(contactId), null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { result += it }
        }
        return result
    }

    private fun emailsForContact(contactId: String): List<String> {
        val result = mutableListOf<String>()
        cr.query(
            ContactEmail.CONTENT_URI, arrayOf(ContactEmail.ADDRESS),
            "${ContactEmail.CONTACT_ID} = ?", arrayOf(contactId), null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { result += it }
        }
        return result
    }

    private fun organizationForContact(contactId: String): String? {
        val orgUri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
        return cr.query(orgUri, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun inferRelationship(contact: ContactInfo?, logs: List<LogEntry>): RelationshipType {
        if (contact == null) return RelationshipType.UNKNOWN
        val org = contact.organization
        if (!org.isNullOrBlank()) return RelationshipType.BUSINESS
        // Frequent short-message contact → personal
        val avgLen = if (logs.isEmpty()) 0 else logs.sumOf { it.message.length } / logs.size
        return if (logs.size > 10 && avgLen < 60) RelationshipType.PERSONAL else RelationshipType.UNKNOWN
    }

    private fun inferTrust(
        contact: ContactInfo?,
        logs: List<LogEntry>,
        tones: List<ToneAnalyzer.ToneProfile>
    ): TrustLevel {
        if (contact == null) return TrustLevel.UNKNOWN
        val phishingRisk = tones.maxOfOrNull { it.phishingRisk } ?: 0f
        val threatRisk   = tones.maxOfOrNull { it.threat } ?: 0f
        return when {
            phishingRisk > 0.5f || threatRisk > 0.5f -> TrustLevel.LOW
            logs.size >= 5 -> TrustLevel.HIGH
            logs.size >= 1 -> TrustLevel.MEDIUM
            else           -> TrustLevel.UNKNOWN
        }
    }

    private fun buildSummary(
        contact: ContactInfo?,
        logs: List<LogEntry>,
        tone: String,
        rel: RelationshipType,
        trust: TrustLevel,
        topics: List<String>
    ): String {
        val name = contact?.displayName ?: "Unknown"
        val org  = contact?.organization?.let { " ($it)" } ?: ""
        val hist = if (logs.isEmpty()) "No history" else "${logs.size} past message(s), tone: $tone"
        val top  = if (topics.isEmpty()) "" else ", topics: ${topics.joinToString(", ")}"
        return "$name$org | $rel | Trust: $trust | $hist$top"
    }
}

private object ContentUris {
    fun withAppendedId(uri: Uri, id: Long): Uri =
        android.content.ContentUris.withAppendedId(uri, id)
}
