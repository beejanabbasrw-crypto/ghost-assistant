package com.ghost.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GhostAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GhostAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active event stream
    }

    override fun onInterrupt() {
        // Interruption callback from Android Accessibility subsystem
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Launches an app using its explicit package name via PackageManager.
     */
    fun launchApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return true
    }

    /**
     * Traverses the active window hierarchy to locate an element with matching text and clicks it.
     */
    fun clickElementByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = root.findAccessibilityNodeInfosByText(text)
        
        for (node in matchedNodes) {
            if (performClickOnNodeOrParent(node)) {
                return true
            }
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    fun performGlobalBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performGlobalHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performGlobalRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
}
