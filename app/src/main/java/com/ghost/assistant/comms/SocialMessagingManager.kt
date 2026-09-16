package com.ghost.assistant.comms

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Locale

object SocialMessagingManager {

    enum class MessagePlatform {
        WHATSAPP,
        INSTAGRAM,
        FACEBOOK,
        SMS
    }

    data class ContactInfo(
        val displayName: String,
        val phoneNumber: String
    )

    data class MessagingDirective(
        val platform: MessagePlatform,
        val targetContact: String,
        val messageText: String
    )

    fun resolveContact(context: Context, query: String): ContactInfo? {
        val cleanQuery = query.trim().lowercase(Locale.US)
        if (cleanQuery.isBlank()) return null

        // If query is already a phone number (e.g. +123456 or 9876543)
        val digitsOnly = cleanQuery.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 6) {
            return ContactInfo(displayName = digitsOnly, phoneNumber = digitsOnly)
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("SocialMessagingManager", "READ_CONTACTS permission not granted.")
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
            Log.e("SocialMessagingManager", "Error querying contacts: ${e.message}", e)
        }

        return exactMatch ?: prefixMatch ?: containsMatch
    }

    fun parseDirective(rawText: String): MessagingDirective? {
        val lower = rawText.trim().lowercase(Locale.US)

        // Determine platform
        val platform = when {
            lower.contains("whatsapp") || lower.contains("whats app") -> MessagePlatform.WHATSAPP
            lower.contains("instagram") || lower.contains("insta ") || lower.contains("insta direct") -> MessagePlatform.INSTAGRAM
            lower.contains("facebook") || lower.contains("messenger") || lower.contains("fb ") -> MessagePlatform.FACEBOOK
            else -> MessagePlatform.SMS
        }

        // Patterns to match:
        // 1. send (a) message on <platform> to <contact> (saying|that|:) <message>
        val pattern1 = Regex("^(?:send\\s+(?:a\\s+)?message\\s+on\\s+(?:whatsapp|whats\\s+app|instagram|facebook|messenger|sms)\\s+to\\s+)(.+?)(?:\\s+(?:saying|that|:)\\s+|\\s+)(.+)$", RegexOption.IGNORE_CASE)
        val m1 = pattern1.find(rawText)
        if (m1 != null) {
            val contact = m1.groupValues[1].trim()
            val msg = m1.groupValues[2].trim()
            return MessagingDirective(platform, contact, msg)
        }

        // 2. send (a) message to <contact> on <platform> (saying|that|:) <message>
        val pattern2 = Regex("^(?:send\\s+(?:a\\s+)?(?:message|text|sms)\\s+to\\s+)(.+?)\\s+on\\s+(?:whatsapp|whats\\s+app|instagram|facebook|messenger)(?:\\s+(?:saying|that|:)\\s+|\\s+)(.+)$", RegexOption.IGNORE_CASE)
        val m2 = pattern2.find(rawText)
        if (m2 != null) {
            val contact = m2.groupValues[1].trim()
            val msg = m2.groupValues[2].trim()
            return MessagingDirective(platform, contact, msg)
        }

        // 3. send (a) (whatsapp|text|message) to <contact> (saying|that|:) <message>
        val pattern3 = Regex("^(?:send\\s+(?:a\\s+)?(?:whatsapp|text|message|sms)\\s+to\\s+)(.+?)(?:\\s+(?:saying|that|:)\\s+|\\s+)(.+)$", RegexOption.IGNORE_CASE)
        val m3 = pattern3.find(rawText)
        if (m3 != null) {
            val contact = m3.groupValues[1].trim()
            val msg = m3.groupValues[2].trim()
            return MessagingDirective(platform, contact, msg)
        }

        // 4. (whatsapp|instagram|facebook|message|text|sms) <contact> (saying|that|:) <message>
        val pattern4 = Regex("^(?:whatsapp|whats\\s+app|instagram|facebook|messenger|message|text|sms)\\s+(?:to\\s+)?(.+?)(?:\\s+(?:saying|that|:)\\s+|\\s+)(.+)$", RegexOption.IGNORE_CASE)
        val m4 = pattern4.find(rawText)
        if (m4 != null) {
            val contact = m4.groupValues[1].trim()
            val msg = m4.groupValues[2].trim()
            return MessagingDirective(platform, contact, msg)
        }

        // 5. send message to <contact> <message>
        val pattern5 = Regex("^(?:send\\s+(?:a\\s+)?message\\s+to\\s+)(.+?)(?:\\s+(?:saying|that|:)\\s+|\\s+)(.+)$", RegexOption.IGNORE_CASE)
        val m5 = pattern5.find(rawText)
        if (m5 != null) {
            val contact = m5.groupValues[1].trim()
            val msg = m5.groupValues[2].trim()
            return MessagingDirective(platform, contact, msg)
        }

        return null
    }

    fun dispatchMessage(
        context: Context,
        directive: MessagingDirective,
        userName: String
    ): Pair<Boolean, String> {
        return when (directive.platform) {
            MessagePlatform.WHATSAPP -> dispatchWhatsApp(context, directive.targetContact, directive.messageText, userName)
            MessagePlatform.INSTAGRAM -> dispatchInstagram(context, directive.targetContact, directive.messageText, userName)
            MessagePlatform.FACEBOOK -> dispatchFacebook(context, directive.targetContact, directive.messageText, userName)
            MessagePlatform.SMS -> dispatchSms(context, directive.targetContact, directive.messageText, userName)
        }
    }

    private fun dispatchWhatsApp(
        context: Context,
        target: String,
        message: String,
        userName: String
    ): Pair<Boolean, String> {
        val resolved = resolveContact(context, target)
        val encodedMsg = try { URLEncoder.encode(message, "UTF-8") } catch (_: Exception) { message }

        if (resolved != null) {
            val cleanedPhone = resolved.phoneNumber.replace(Regex("[^0-9]"), "")
            val url = "https://api.whatsapp.com/send?phone=$cleanedPhone&text=$encodedMsg"
            val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            return try {
                context.startActivity(waIntent)
                true to "Opening WhatsApp conversation with ${resolved.displayName} for you, $userName."
            } catch (e: Exception) {
                // Fallback without package restriction (e.g. WhatsApp Business or browser)
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                    true to "Opening WhatsApp link with ${resolved.displayName} for you, $userName."
                } catch (e2: Exception) {
                    false to "WhatsApp is not installed on this device, $userName."
                }
            }
        } else {
            // Check if target has digits
            val digits = target.replace(Regex("[^0-9]"), "")
            if (digits.length >= 6) {
                val url = "https://api.whatsapp.com/send?phone=$digits&text=$encodedMsg"
                val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return try {
                    context.startActivity(waIntent)
                    true to "Opening WhatsApp conversation with $target for you, $userName."
                } catch (e: Exception) {
                    false to "Failed to open WhatsApp, $userName."
                }
            }

            // Contact not found in phonebook, open share sheet with WhatsApp
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(shareIntent)
                true to "I could not locate $target in your phonebook, $userName. Opening WhatsApp contact picker."
            } catch (e: Exception) {
                false to "Target $target could not be resolved in your contacts, $userName."
            }
        }
    }

    private fun dispatchInstagram(
        context: Context,
        target: String,
        message: String,
        userName: String
    ): Pair<Boolean, String> {
        val cleanUsername = target.replace(Regex("[^a-zA-Z0-9_.]"), "").trim()
        val directUrl = if (cleanUsername.isNotBlank()) "https://ig.me/m/$cleanUsername" else "https://instagram.com/direct/inbox/"
        
        val igIntent = Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)).apply {
            setPackage("com.instagram.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(igIntent)
            true to "Opening Instagram Direct conversation with $target for you, $userName."
        } catch (e: Exception) {
            // Fallback to share intent or browser
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    setPackage("com.instagram.android")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(shareIntent)
                true to "Opening Instagram Direct dispatch for you, $userName."
            } catch (e2: Exception) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(directUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    true to "Opening Instagram portal for $target for you, $userName."
                } catch (e3: Exception) {
                    false to "Instagram is not available on this device, $userName."
                }
            }
        }
    }

    private fun dispatchFacebook(
        context: Context,
        target: String,
        message: String,
        userName: String
    ): Pair<Boolean, String> {
        val cleanTarget = target.replace(Regex("[^a-zA-Z0-9_.]"), "").trim()
        val messengerUrl = if (cleanTarget.isNotBlank()) "https://m.me/$cleanTarget" else "https://m.me"

        val messengerIntent = Intent(Intent.ACTION_VIEW, Uri.parse(messengerUrl)).apply {
            setPackage("com.facebook.orca")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(messengerIntent)
            true to "Opening Messenger conversation with $target for you, $userName."
        } catch (e: Exception) {
            try {
                val fbIntent = Intent(Intent.ACTION_VIEW, Uri.parse(messengerUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fbIntent)
                true to "Opening Facebook Messenger for $target for you, $userName."
            } catch (e2: Exception) {
                false to "Facebook Messenger is not available on this device, $userName."
            }
        }
    }

    private fun dispatchSms(
        context: Context,
        target: String,
        message: String,
        userName: String
    ): Pair<Boolean, String> {
        val resolved = resolveContact(context, target)
        val phoneNumber = resolved?.phoneNumber ?: run {
            val digits = target.replace(Regex("[^0-9+]"), "")
            if (digits.length >= 3) digits else null
        }

        if (phoneNumber == null) {
            return false to "I could not locate $target in your contacts, $userName."
        }

        val displayName = resolved?.displayName ?: phoneNumber
        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(smsIntent)
            true to "Dispatching message to $displayName for you, $userName."
        } catch (e: Exception) {
            false to "SMS transmission failure for $displayName, $userName."
        }
    }
}
