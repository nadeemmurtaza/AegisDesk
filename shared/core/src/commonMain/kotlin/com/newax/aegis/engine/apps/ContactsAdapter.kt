package com.newax.aegis.engine.apps

data class ContactInfo(
    val id: String,
    val lookupKey: String,
    val displayName: String,
    val phoneNumbers: List<String>,
    val emails: List<String>
)

interface ContactsAdapter {
    fun findContactByName(name: String): ContactInfo?
    fun getAllContacts(): List<ContactInfo>
    fun deleteContact(id: String): Boolean
    fun analyzeContacts(): String
    fun mergeContacts(id1: String, id2: String): Boolean
    fun getPersonProfile(id: String): String?
    fun buildPersonProfile(id: String): Boolean
}
