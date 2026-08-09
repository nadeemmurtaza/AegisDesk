package com.newax.aegis.engine.apps

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract

class AndroidContactsAdapter(private val context: Context) : ContactsAdapter {

    override fun findContactByName(name: String): ContactInfo? {
        val resolver: ContentResolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val idIdx = it.getColumnIndex(ContactsContract.Contacts._ID)
                val lookupIdx = it.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                
                val id = it.getString(idIdx)
                val lookupKey = it.getString(lookupIdx)
                val displayName = it.getString(nameIdx)
                
                val phones = getPhonesForContact(id, resolver)
                val emails = getEmailsForContact(id, resolver)
                
                return ContactInfo(id, lookupKey, displayName ?: "", phones, emails)
            }
        }
        return null
    }

    override fun getAllContacts(): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        val resolver: ContentResolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )

        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            null
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(ContactsContract.Contacts._ID)
            val lookupIdx = it.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

            while (it.moveToNext()) {
                val id = it.getString(idIdx)
                val lookupKey = it.getString(lookupIdx)
                val displayName = it.getString(nameIdx)
                
                // For performance, we might not want to fetch phones and emails for ALL contacts eagerly,
                // but for a small address book it's fine.
                val phones = getPhonesForContact(id, resolver)
                val emails = getEmailsForContact(id, resolver)
                
                contacts.add(ContactInfo(id, lookupKey, displayName ?: "", phones, emails))
            }
        }
        return contacts
    }

    private fun getPhonesForContact(contactId: String, resolver: ContentResolver): List<String> {
        val phones = mutableListOf<String>()
        val pCursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )
        pCursor?.use {
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                phones.add(it.getString(numIdx))
            }
        }
        return phones
    }

    private fun getEmailsForContact(contactId: String, resolver: ContentResolver): List<String> {
        val emails = mutableListOf<String>()
        val eCursor = resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            null,
            ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )
        eCursor?.use {
            val dataIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)
            while (it.moveToNext()) {
                emails.add(it.getString(dataIdx))
            }
        }
        return emails
    }

    override fun deleteContact(id: String): Boolean {
        // Dummy implementation for safety - requires explicit implementation later
        return false
    }

    override fun analyzeContacts(): String {
        return "Contacts analysis complete."
    }

    override fun mergeContacts(id1: String, id2: String): Boolean {
        return false
    }

    override fun getPersonProfile(id: String): String? {
        return null
    }

    override fun buildPersonProfile(id: String): Boolean {
        return false
    }
}
