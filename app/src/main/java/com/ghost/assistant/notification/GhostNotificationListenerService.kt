package com.ghost.assistant.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

class GhostNotificationListenerService : NotificationListenerService() {

    data class NotificationItem(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val timestamp: Long,
        val replyAction: Notification.Action?
    )

    companion object {
        private const val MAX_HISTORY = 25
        private val recentNotifications = ConcurrentLinkedDeque<NotificationItem>()
        var isServiceConnected: Boolean = false
            private set

        /**
         * Reads the most recent notifications aloud.
         */
        fun getRecentNotificationsSummary(context: Context, userName: String): String {
            if (!isServiceConnected) {
                return "Notification Access is not enabled for Ghost. Please grant access in Android Settings, $userName."
            }

            val validNotifications = recentNotifications.toList()
                .filter { it.title.isNotBlank() || it.text.isNotBlank() }
                .take(4)

            if (validNotifications.isEmpty()) {
                return "You have no unread notifications on your device, $userName."
            }

            val sb = StringBuilder("You have ${validNotifications.size} recent notification${if (validNotifications.size > 1) "s" else ""}, $userName: ")
            for ((idx, item) in validNotifications.withIndex()) {
                if (idx > 0) sb.append(". Next, ")
                sb.append("from ${item.appName}: ${item.title}")
                if (item.text.isNotBlank() && item.text != item.title) {
                    sb.append(", \"${item.text}\"")
                }
            }
            sb.append(".")
            return sb.toString()
        }

        /**
         * Replies to the most recent notification that supports RemoteInput.
         */
        fun replyToLatestNotification(context: Context, replyMessage: String, userName: String): String {
            if (!isServiceConnected) {
                return "Notification Access is required to send voice replies, $userName."
            }

            val targetItem = recentNotifications.toList().firstOrNull { it.replyAction != null }
                ?: return "No recent replyable notification found to respond to, $userName."

            val action = targetItem.replyAction ?: return "No reply action available, $userName."
            val remoteInputs = action.remoteInputs ?: return "Reply channel not supported for ${targetItem.appName}, $userName."

            val input = remoteInputs.firstOrNull() ?: return "Reply input not found, $userName."

            try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(input.resultKey, replyMessage)
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

                action.actionIntent.send(context, 0, intent)
                return "Dispatched reply to ${targetItem.appName}: \"$replyMessage\", $userName."
            } catch (e: Exception) {
                Log.e("GhostNotification", "Reply failed: ${e.message}", e)
                return "Failed to send notification reply to ${targetItem.appName}, $userName."
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        Log.i("GhostNotification", "GhostNotificationListenerService connected.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        // Find reply action with RemoteInput
        var replyAction: Notification.Action? = null
        notification.actions?.let { actions ->
            for (action in actions) {
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    replyAction = action
                    break
                }
            }
        }

        val item = NotificationItem(
            key = sbn.key ?: "${sbn.packageName}_${sbn.id}",
            packageName = sbn.packageName,
            appName = appName,
            title = title.trim(),
            text = text.trim(),
            timestamp = sbn.postTime,
            replyAction = replyAction
        )

        recentNotifications.addFirst(item)
        while (recentNotifications.size > MAX_HISTORY) {
            recentNotifications.removeLast()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val key = sbn.key ?: "${sbn.packageName}_${sbn.id}"
        recentNotifications.removeAll { it.key == key }
    }
}
