package com.newax.aegis.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Background service that scans the phone's address book on demand.
 */
class ContactScannerService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("AegisContacts", "Starting On-Demand Contact Scan...")
        serviceScope.launch {
            scanContacts()
        }
        return START_NOT_STICKY
    }

    private fun scanContacts() {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        )

        val contactList = StringBuilder()
        var count = 0

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIndex = it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val hasPhoneIndex = it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            while (it.moveToNext() && count < 100) {
                val id = it.getString(idIndex)
                val name = it.getString(nameIndex) ?: "Unknown"
                val hasPhone = it.getInt(hasPhoneIndex) > 0

                contactList.append("ID: $id, Name: $name, HasPhone: $hasPhone\n")
                count++
            }
        }

        if (contactList.isNotEmpty()) {
            val systemPrompt = "[Contact List Scan]\n$contactList\n\nAnalyze this contact list. If you see exact duplicate names or contacts without a phone number, output ONLY 'delete contact ID'. Otherwise, state 'Contacts look clean'."
            TriggerEngine.triggerEvents.tryEmit(systemPrompt)
        }

        Log.i("AegisContacts", "On-Demand Contact Scan Completed.")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
