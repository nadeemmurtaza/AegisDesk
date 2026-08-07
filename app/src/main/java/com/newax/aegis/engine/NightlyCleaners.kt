package com.newax.aegis.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class GalleryScannerWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.i("AegisScanner", "Nightly Gallery Scan via WorkManager Started")
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
            val cursor = applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                var count = 0
                while (it.moveToNext() && count < 50) {
                    val path = it.getString(dataColumn)
                    try {
                        val bitmap = BitmapFactory.decodeFile(path)
                        if (bitmap != null) {
                            // In advanced implementation, we pass the raw file path or Bitmap to the AI natively.
                            // For this prototype, we simulate visual analysis by prompting TriggerEngine.
                            val prompt = "[Gallery Scan: $path] Analyze this image visually. If it's blurry or junk, output 'delete file $path'. Otherwise, output 'Looks good'."
                            TriggerEngine.triggerEvents.tryEmit(prompt)
                        }
                    } catch (e: Exception) {
                        Log.e("AegisScanner", "Failed to decode $path")
                    }
                    count++
                }
            }
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}

class ContactScannerWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.i("AegisScanner", "Nightly Contact Scan via WorkManager Started")
        try {
            val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val cursor = applicationContext.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, projection, null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )

            val contactList = java.lang.StringBuilder()
            var count = 0

            cursor?.use {
                val idIndex = it.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameIndex = it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val hasPhoneIndex = it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (it.moveToNext() && count < 100) {
                    contactList.append("ID: ${it.getString(idIndex)}, Name: ${it.getString(nameIndex)}, HasPhone: ${it.getInt(hasPhoneIndex) > 0}\n")
                    count++
                }
            }

            if (contactList.isNotEmpty()) {
                TriggerEngine.triggerEvents.tryEmit(
                    "[Contact List Scan]\n$contactList\n\nAnalyze this contact list. Output 'delete contact ID' for exact duplicates or empty numbers."
                )
            }
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}
