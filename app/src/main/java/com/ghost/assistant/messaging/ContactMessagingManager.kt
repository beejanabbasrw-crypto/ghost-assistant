package com.ghost.assistant.messaging

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Locale

object ContactMessagingManager {

    data class ContactInfo(
        val displayName: String,
        val phoneNumber: String
    )

    /**
     * Resolves a contact query string against ContactsContract.CommonDataKinds.Phone.
     */
    fun resolveContact(context: Context, query: String): ContactInfo? {
        val cleanQuery = query.trim().lowercase(Locale.US)
        if (cleanQuery.isBlank()) return null

        // Direct digits only check (e.g. "+1234567890" or "987654321")
        val digitsOnly = cleanQuery.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 6) {
            return ContactInfo(displayName = digitsOnly, phoneNumber = digitsOnly)
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("ContactMessagingManager", "READ_CONTACTS permission not granted.")
            return null
        }

        var exactMatch: ContactInfo? = null
        var prefixMatch: ContactInfo? = null
        var containsMatch: ContactInfo? = null

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )

            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else continue
                    val number = if (numberIdx >= 0) c.getString(numberIdx) else continue
                    if (name.isNullOrBlank() || number.isNullOrBlank()) continue

                    val cleanName = name.lowercase(Locale.US).trim()
                    if (cleanName == cleanQuery) {
                        exactMatch = ContactInfo(displayName = name, phoneNumber = number)
                        break
                    } else if (cleanName.startsWith(cleanQuery) && prefixMatch == null) {
                        prefixMatch = ContactInfo(displayName = name, phoneNumber = number)
                    } else if (cleanName.contains(cleanQuery) && containsMatch == null) {
                        containsMatch = ContactInfo(displayName = name, phoneNumber = number)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactMessagingManager", "Error querying contacts: ${e.message}", e)
        }

        return exactMatch ?: prefixMatch ?: containsMatch
    }

    /**
     * Sends a message to a resolved or raw contact.
     * Uses direct SmsManager if SEND_SMS is granted, or hands off to WhatsApp if requested/installed.
     */
    fun sendMessage(
        context: Context,
        targetContact: String,
        messageText: String,
        preferWhatsApp: Boolean,
        userName: String
    ): String {
        val resolved = resolveContact(context, targetContact)
        val rawDigits = targetContact.replace(Regex("[^0-9+]"), "")
        val phoneNumber = resolved?.phoneNumber ?: if (rawDigits.length >= 6) rawDigits else null
        val displayName = resolved?.displayName ?: targetContact

        // 1. If WhatsApp is preferred or explicitly targeted
        if (preferWhatsApp) {
            return sendViaWhatsApp(context, displayName, phoneNumber, messageText, userName)
        }

        // 2. Direct SMS sending via SmsManager if SEND_SMS permission is granted
        if (phoneNumber != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                return try {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }

                    val parts = smsManager.divideMessage(messageText)
                    if (parts.size > 1) {
                        smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                    } else {
                        smsManager.sendTextMessage(phoneNumber, null, messageText, null, null)
                    }
                    "Direct SMS transmitted to $displayName: \"$messageText\""
                } catch (e: Exception) {
                    Log.e("ContactMessagingManager", "SmsManager failure: ${e.message}", e)
                    // Fallback to launching SMS intent
                    launchSmsIntent(context, phoneNumber, messageText)
                    "SMS dispatch opened for $displayName, $userName."
                }
            } else {
                // If SMS permission not granted, check if WhatsApp is installed as seamless alternative
                if (isWhatsAppInstalled(context)) {
                    return sendViaWhatsApp(context, displayName, phoneNumber, messageText, userName)
                }

                // Otherwise launch the system SMS app
                launchSmsIntent(context, phoneNumber, messageText)
                return "SMS permission not granted. Opening messaging app for $displayName, $userName."
            }
        }

        // 3. Contact not found in phonebook
        if (isWhatsAppInstalled(context)) {
            // Open WhatsApp share sheet with the message
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, messageText)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(shareIntent)
                return "I couldn't locate $targetContact in your contacts, $userName. Opening WhatsApp picker."
            } catch (_: Exception) {}
        }

        return "I was unable to locate $targetContact in your contact list, $userName."
    }

    private fun sendViaWhatsApp(
        context: Context,
        displayName: String,
        phoneNumber: String?,
        messageText: String,
        userName: String
    ): String {
        val encodedMsg = try { URLEncoder.encode(messageText, "UTF-8") } catch (_: Exception) { messageText }

        if (phoneNumber != null) {
            val cleanPhone = phoneNumber.replace(Regex("[^0-9]"), "")
            val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg"
            val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(waIntent)
                return "Opening WhatsApp conversation with $displayName for you, $userName."
            } catch (_: Exception) {
                // Try without package restriction (e.g. WhatsApp Business or browser)
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                    return "Opening WhatsApp link with $displayName for you, $userName."
                } catch (_: Exception) {}
            }
        }

        // WhatsApp share sheet fallback
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, messageText)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(shareIntent)
            "Opening WhatsApp to dispatch message to $displayName, $userName."
        } catch (_: Exception) {
            "WhatsApp is not installed on this device, $userName."
        }
    }

    private fun launchSmsIntent(context: Context, phoneNumber: String, messageText: String) {
        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
            putExtra("sms_body", messageText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(smsIntent)
        } catch (_: Exception) {}
    }

    fun isWhatsAppInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return pm.getLaunchIntentForPackage("com.whatsapp") != null ||
                pm.getLaunchIntentForPackage("com.whatsapp.w4b") != null
    }
}
