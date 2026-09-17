package com.ghost.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ghost.assistant.theme.ThemeManager
import com.ghost.assistant.wakeword.GhostWakeWordService

class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (ThemeManager.isWakeWordEnabled(context)) {
                GhostWakeWordService.startService(context)
            }
        }
    }
}
