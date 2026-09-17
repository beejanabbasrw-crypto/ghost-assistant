package com.ghost.assistant.router

import com.ghost.assistant.device.DeviceToggleManager
import com.ghost.assistant.media.MusicManager
import java.util.Locale
import java.util.regex.Pattern

sealed class GhostIntent {
    // 1. Device Toggles
    data class DeviceToggle(
        val device: DeviceToggleManager.DeviceType,
        val action: DeviceToggleManager.ToggleAction
    ) : GhostIntent()

    // 2. Play Music
    data class PlayMusic(val query: String?) : GhostIntent()
    data class MediaControl(val action: MusicManager.MediaControlAction) : GhostIntent()

    // 3. Messaging
    data class SendMessage(
        val contact: String,
        val messageText: String,
        val preferWhatsApp: Boolean
    ) : GhostIntent()

    // 4. Calls
    data class CallContact(val target: String) : GhostIntent()

    // 5. Hardware: Torch
    data class Flashlight(val action: DeviceToggleManager.ToggleAction) : GhostIntent()

    // 6. Volume Control
    enum class VolumeAction { UP, DOWN, MUTE, UNMUTE, SET_PERCENT }
    data class VolumeControl(val action: VolumeAction, val percent: Int? = null) : GhostIntent()

    // 7. Brightness Control
    enum class BrightnessAction { UP, DOWN, MAX, MIN, SET_PERCENT }
    data class BrightnessControl(val action: BrightnessAction, val percent: Int? = null) : GhostIntent()

    // 8. Open App
    data class OpenApp(val appName: String) : GhostIntent()

    // 9. Alarms & Reminders & Timers
    data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : GhostIntent()
    data class SetTimer(val seconds: Int, val label: String?) : GhostIntent()
    data class SetReminder(val text: String) : GhostIntent()

    // 10. Calendar
    object ReadCalendar : GhostIntent()

    // 11. Notifications
    object ReadNotifications : GhostIntent()
    data class ReplyNotification(val text: String) : GhostIntent()

    // 12. Battery & Power
    object BatteryStatus : GhostIntent()

    // 13. Weather
    data class Weather(val location: String?) : GhostIntent()

    // 14. Screenshot & Lock Screen
    object TakeScreenshot : GhostIntent()
    object LockScreen : GhostIntent()

    // 15. Laptop Companion
    data class CompanionCommand(val command: String, val arg: String) : GhostIntent()

    // 16. Accessibility Navigation
    enum class NavAction { BACK, HOME, RECENTS, NOTIFICATIONS, CLICK }
    data class SystemNavigation(val action: NavAction, val targetText: String? = null) : GhostIntent()

    // 17. System Info & Utilities
    data class DateTime(val isTime: Boolean) : GhostIntent()
    object SystemStatus : GhostIntent()
    object Help : GhostIntent()
    object Greeting : GhostIntent()
    object Identity : GhostIntent()
    data class WebSearch(val query: String) : GhostIntent()

    // Fallback
    data class Unknown(val rawQuery: String) : GhostIntent()
}

object IntentRouter {

    private val WAKE_WORD_PATTERN = Pattern.compile(
        "^(?:hey\\s+|ok\\s+|okay\\s+|yo\\s+)?(?:ghost|jarvis)(?:[,\\s]+(?:please\\s+)?)?",
        Pattern.CASE_INSENSITIVE
    )

    private val POLITE_PREFIXES = listOf(
        "can you please", "could you please", "would you please",
        "please can you", "please could you", "please",
        "can you", "could you", "would you", "will you",
        "i want to", "i'd like to", "tell me", "just"
    )

    /**
     * Normalizes the voice input by stripping wake words and filler phrasing.
     */
    fun normalize(input: String): String {
        var text = input.trim()

        // 1. Remove wake word invocation prefix
        val wakeMatcher = WAKE_WORD_PATTERN.matcher(text)
        if (wakeMatcher.find()) {
            text = text.substring(wakeMatcher.end()).trim()
        }

        // 2. Remove common polite prefixes
        val lower = text.lowercase(Locale.US)
        for (prefix in POLITE_PREFIXES) {
            if (lower.startsWith("$prefix ")) {
                text = text.substring(prefix.length).trim()
                break
            }
        }

        // 3. Remove punctuation
        text = text.replace(Regex("[.,?!;]"), "").trim()
        return text
    }

    /**
     * Maps recognized speech to structured GhostIntent with fuzzy matching and pattern fallback.
     */
    fun routeIntent(rawInput: String): GhostIntent {
        val normalized = normalize(rawInput)
        val lower = normalized.lowercase(Locale.US)

        if (lower.isBlank()) {
            return GhostIntent.Greeting
        }

        // 1. Device Toggles — Bluetooth, Wifi, Hotspot, Mobile Data
        parseDeviceToggle(lower)?.let { return it }

        // 2. Music & Media Controls
        parseMusicIntent(normalized, lower)?.let { return it }

        // 3. Messaging — "message [contact] [text]"
        parseMessagingIntent(normalized, lower)?.let { return it }

        // 4. Phone Calls — "call [contact]"
        parseCallIntent(normalized, lower)?.let { return it }

        // 5. Flashlight / Torch
        parseFlashlightIntent(lower)?.let { return it }

        // 6. Volume Control
        parseVolumeIntent(lower)?.let { return it }

        // 7. Brightness Control
        parseBrightnessIntent(lower)?.let { return it }

        // 8. Alarms, Timers, Reminders
        parseAlarmAndTimerIntent(normalized, lower)?.let { return it }

        // 9. Calendar
        if (lower.contains("calendar") || lower.contains("schedule") || lower.contains("what's on today") || lower.contains("my agenda")) {
            return GhostIntent.ReadCalendar
        }

        // 10. Notifications
        if (lower.startsWith("reply to notification") || lower.startsWith("reply notification") || lower.startsWith("reply ")) {
            val replyText = normalized.replace(Regex("^(?:reply\\s+to\\s+notification|reply\\s+notification|reply)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (replyText.isNotBlank()) return GhostIntent.ReplyNotification(replyText)
        }
        if (lower.contains("notification") || lower.contains("read messages") || lower.contains("check messages")) {
            return GhostIntent.ReadNotifications
        }

        // 11. Battery Status
        if (lower.contains("battery") || lower.contains("power status") || lower.contains("charge level")) {
            return GhostIntent.BatteryStatus
        }

        // 12. Weather Lookup
        parseWeatherIntent(normalized, lower)?.let { return it }

        // 13. Screenshot & Lock Screen
        if (lower.contains("screenshot") || lower.contains("capture screen") || lower.contains("take a screen shot")) {
            return GhostIntent.TakeScreenshot
        }
        if (lower.contains("lock screen") || lower.contains("lock phone") || lower.contains("lock device")) {
            return GhostIntent.LockScreen
        }

        // 14. Laptop Companion Mode ("laptop open Chrome", "laptop volume up", "laptop search files ...")
        parseCompanionIntent(normalized, lower)?.let { return it }

        // 15. System Navigation (Back, Home, Recents, Click)
        parseNavigationIntent(normalized, lower)?.let { return it }

        // 16. Open Apps ("open [app]", "launch [app]")
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val appTarget = normalized.replace(Regex("^(open|launch|start)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (appTarget.isNotBlank()) return GhostIntent.OpenApp(appTarget)
        }

        // 17. Time & Date
        if (lower.contains("what time") || lower == "time" || lower.contains("current time")) {
            return GhostIntent.DateTime(isTime = true)
        }
        if (lower.contains("what date") || lower == "date" || lower.contains("today's date") || lower.contains("what day")) {
            return GhostIntent.DateTime(isTime = false)
        }

        // 18. Status & Greetings
        if (lower.contains("status report") || lower.contains("system status") || lower.contains("how are you") || lower.contains("diagnostics")) {
            return GhostIntent.SystemStatus
        }
        if (lower.contains("who are you") || lower.contains("what are you") || lower.contains("your name") || lower.contains("identify yourself")) {
            return GhostIntent.Identity
        }
        if (lower.contains("what can you do") || lower == "help" || lower.contains("commands") || lower.contains("available actions")) {
            return GhostIntent.Help
        }
        if (lower.matches(Regex("^(hello|hi|hey|greetings|morning|afternoon|evening).*"))) {
            return GhostIntent.Greeting
        }

        // 19. Explicit Web Search
        if (lower.startsWith("search for ") || lower.startsWith("google ") || lower.startsWith("who is ") || lower.startsWith("what is ") || lower.startsWith("define ")) {
            val query = normalized.replace(Regex("^(search for|google|who is|what is|define)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (query.isNotBlank()) return GhostIntent.WebSearch(query)
        }

        // 20. Fallback: Unknown intent
        return GhostIntent.Unknown(rawInput)
    }

    private fun parseDeviceToggle(lower: String): GhostIntent? {
        // Bluetooth
        if (lower.contains("bluetooth") || lower.contains("blutooth") || lower.contains("bt")) {
            val action = when {
                lower.contains("on") || lower.contains("enable") || lower.contains("connect") -> DeviceToggleManager.ToggleAction.ON
                lower.contains("off") || lower.contains("disable") || lower.contains("disconnect") -> DeviceToggleManager.ToggleAction.OFF
                else -> DeviceToggleManager.ToggleAction.TOGGLE
            }
            return GhostIntent.DeviceToggle(DeviceToggleManager.DeviceType.BLUETOOTH, action)
        }

        // Wifi
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("wireless network")) {
            val action = when {
                lower.contains("on") || lower.contains("enable") -> DeviceToggleManager.ToggleAction.ON
                lower.contains("off") || lower.contains("disable") -> DeviceToggleManager.ToggleAction.OFF
                else -> DeviceToggleManager.ToggleAction.TOGGLE
            }
            return GhostIntent.DeviceToggle(DeviceToggleManager.DeviceType.WIFI, action)
        }

        // Hotspot
        if (lower.contains("hotspot") || lower.contains("hot spot") || lower.contains("tethering")) {
            val action = when {
                lower.contains("on") || lower.contains("enable") -> DeviceToggleManager.ToggleAction.ON
                lower.contains("off") || lower.contains("disable") -> DeviceToggleManager.ToggleAction.OFF
                else -> DeviceToggleManager.ToggleAction.TOGGLE
            }
            return GhostIntent.DeviceToggle(DeviceToggleManager.DeviceType.HOTSPOT, action)
        }

        // Mobile Data
        if (lower.contains("mobile data") || lower.contains("cellular data") || lower.contains("cellular network") || lower.contains("cellular")) {
            val action = when {
                lower.contains("on") || lower.contains("enable") -> DeviceToggleManager.ToggleAction.ON
                lower.contains("off") || lower.contains("disable") -> DeviceToggleManager.ToggleAction.OFF
                else -> DeviceToggleManager.ToggleAction.TOGGLE
            }
            return GhostIntent.DeviceToggle(DeviceToggleManager.DeviceType.MOBILE_DATA, action)
        }

        return null
    }

    private fun parseMusicIntent(normalized: String, lower: String): GhostIntent? {
        // Media controls
        if (lower == "pause" || lower == "pause music" || lower == "pause song") {
            return GhostIntent.MediaControl(MusicManager.MediaControlAction.PAUSE)
        }
        if (lower == "resume" || lower == "resume music" || lower == "unpause") {
            return GhostIntent.MediaControl(MusicManager.MediaControlAction.PLAY)
        }
        if (lower == "next song" || lower == "next track" || lower == "skip song" || lower == "skip") {
            return GhostIntent.MediaControl(MusicManager.MediaControlAction.NEXT)
        }
        if (lower == "previous song" || lower == "previous track" || lower == "back track") {
            return GhostIntent.MediaControl(MusicManager.MediaControlAction.PREVIOUS)
        }
        if (lower == "stop music" || lower == "stop playing") {
            return GhostIntent.MediaControl(MusicManager.MediaControlAction.STOP)
        }

        // Play query
        if (lower.startsWith("play ") || lower.startsWith("put on ") || lower.startsWith("listen to ")) {
            val query = normalized.replace(Regex("^(play|put on|listen to)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (query.equals("music", ignoreCase = true) || query.equals("some music", ignoreCase = true) || query.isBlank()) {
                return GhostIntent.PlayMusic(null)
            }
            return GhostIntent.PlayMusic(query)
        }

        return null
    }

    private fun parseMessagingIntent(normalized: String, lower: String): GhostIntent? {
        val isWhatsApp = lower.contains("whatsapp") || lower.contains("whats app")

        // Regex 1: "message [contact] [text]" or "text [contact] [text]"
        val match1 = Regex("^(?:send\\s+(?:a\\s+)?(?:message|text|sms)\\s+to|message|text|sms)\\s+([a-zA-Z0-9+_\\s]+?)(?:\\s+(?:saying|that|:)\\s+|\\s+)(.+)$", RegexOption.IGNORE_CASE).find(normalized)
        if (match1 != null) {
            val contact = match1.groupValues[1].replace(Regex("on\\s+whatsapp", RegexOption.IGNORE_CASE), "").trim()
            val msg = match1.groupValues[2].trim()
            return GhostIntent.SendMessage(contact, msg, isWhatsApp)
        }

        // Regex 2: "whatsapp [contact] [text]"
        val match2 = Regex("^(?:whatsapp|whats\\s+app)\\s+(?:to\\s+)?([a-zA-Z0-9+_\\s]+?)(?:\\s+(?:saying|that|:)\\s+|\\s+)(.+)$", RegexOption.IGNORE_CASE).find(normalized)
        if (match2 != null) {
            val contact = match2.groupValues[1].trim()
            val msg = match2.groupValues[2].trim()
            return GhostIntent.SendMessage(contact, msg, preferWhatsApp = true)
        }

        return null
    }

    private fun parseCallIntent(normalized: String, lower: String): GhostIntent? {
        if (lower.startsWith("call ") || lower.startsWith("dial ") || lower.startsWith("phone ")) {
            val target = normalized.replace(Regex("^(call|dial|phone)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (target.isNotBlank()) {
                return GhostIntent.CallContact(target)
            }
        }
        return null
    }

    private fun parseFlashlightIntent(lower: String): GhostIntent? {
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash light") || lower.contains("illuminator")) {
            val action = when {
                lower.contains("on") || lower.contains("enable") -> DeviceToggleManager.ToggleAction.ON
                lower.contains("off") || lower.contains("disable") -> DeviceToggleManager.ToggleAction.OFF
                else -> DeviceToggleManager.ToggleAction.TOGGLE
            }
            return GhostIntent.Flashlight(action)
        }
        return null
    }

    private fun parseVolumeIntent(lower: String): GhostIntent? {
        if (lower.contains("volume") || lower.contains("sound")) {
            // Check percentage: "set volume to 80%" or "volume 50 percent"
            val pctMatch = Regex("(\\d{1,3})\\s*(?:%|percent)").find(lower)
            if (pctMatch != null) {
                val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
                if (pct != null) return GhostIntent.VolumeControl(GhostIntent.VolumeAction.SET_PERCENT, pct)
            }

            if (lower.contains("up") || lower.contains("increase") || lower.contains("louder") || lower.contains("raise")) {
                return GhostIntent.VolumeControl(GhostIntent.VolumeAction.UP)
            }
            if (lower.contains("down") || lower.contains("decrease") || lower.contains("lower") || lower.contains("quieter")) {
                return GhostIntent.VolumeControl(GhostIntent.VolumeAction.DOWN)
            }
            if (lower.contains("mute")) {
                return GhostIntent.VolumeControl(GhostIntent.VolumeAction.MUTE)
            }
            if (lower.contains("unmute")) {
                return GhostIntent.VolumeControl(GhostIntent.VolumeAction.UNMUTE)
            }
        }
        return null
    }

    private fun parseBrightnessIntent(lower: String): GhostIntent? {
        if (lower.contains("brightness")) {
            val pctMatch = Regex("(\\d{1,3})\\s*(?:%|percent)").find(lower)
            if (pctMatch != null) {
                val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
                if (pct != null) return GhostIntent.BrightnessControl(GhostIntent.BrightnessAction.SET_PERCENT, pct)
            }
            if (lower.contains("max") || lower.contains("100%")) {
                return GhostIntent.BrightnessControl(GhostIntent.BrightnessAction.MAX)
            }
            if (lower.contains("min") || lower.contains("lowest")) {
                return GhostIntent.BrightnessControl(GhostIntent.BrightnessAction.MIN)
            }
            if (lower.contains("up") || lower.contains("increase") || lower.contains("higher") || lower.contains("brighter")) {
                return GhostIntent.BrightnessControl(GhostIntent.BrightnessAction.UP)
            }
            if (lower.contains("down") || lower.contains("decrease") || lower.contains("lower") || lower.contains("dimmer")) {
                return GhostIntent.BrightnessControl(GhostIntent.BrightnessAction.DOWN)
            }
        }
        return null
    }

    private fun parseAlarmAndTimerIntent(normalized: String, lower: String): GhostIntent? {
        // Alarms: "set alarm for 7:30 am", "alarm at 8 am", "wake me up at 6:45"
        if (lower.contains("alarm") || lower.contains("wake me up")) {
            val timeMatch = Regex("(?:for|at)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(lower)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toIntOrNull() ?: 7
                val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
                val amPm = timeMatch.groupValues[3].lowercase(Locale.US)

                if (amPm == "pm" && hour < 12) hour += 12
                if (amPm == "am" && hour == 12) hour = 0

                val label = if (lower.contains("for ") && !lower.endsWith(timeMatch.value.trim())) {
                    lower.substringAfterLast(timeMatch.value.trim()).trim().ifBlank { null }
                } else null

                return GhostIntent.SetAlarm(hour, minute, label)
            }
        }

        // Timers: "set timer for 10 minutes", "timer 5 minutes", "timer 30 seconds"
        if (lower.contains("timer")) {
            var totalSeconds = 0
            val minMatch = Regex("(\\d+)\\s*(?:min|minute|minutes)").find(lower)
            if (minMatch != null) {
                totalSeconds += (minMatch.groupValues[1].toIntOrNull() ?: 0) * 60
            }
            val secMatch = Regex("(\\d+)\\s*(?:sec|second|seconds)").find(lower)
            if (secMatch != null) {
                totalSeconds += secMatch.groupValues[1].toIntOrNull() ?: 0
            }
            val hrMatch = Regex("(\\d+)\\s*(?:hr|hour|hours)").find(lower)
            if (hrMatch != null) {
                totalSeconds += (hrMatch.groupValues[1].toIntOrNull() ?: 0) * 3600
            }

            if (totalSeconds > 0) {
                return GhostIntent.SetTimer(totalSeconds, null)
            }
        }

        // Reminders: "remind me to call Mom", "set a reminder to buy milk"
        if (lower.startsWith("remind me to ") || lower.startsWith("remind me ") || lower.startsWith("set a reminder to ") || lower.startsWith("reminder ")) {
            val text = normalized.replace(Regex("^(remind me to|remind me|set a reminder to|reminder)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (text.isNotBlank()) return GhostIntent.SetReminder(text)
        }

        return null
    }

    private fun parseWeatherIntent(normalized: String, lower: String): GhostIntent? {
        if (lower.contains("weather") || lower.contains("temperature outside") || lower.contains("forecast")) {
            val locationMatch = Regex("(?:in|for|at)\\s+([a-zA-Z\\s]+)$", RegexOption.IGNORE_CASE).find(normalized)
            val city = locationMatch?.groupValues?.get(1)?.trim()
            return GhostIntent.Weather(city)
        }
        return null
    }

    private fun parseCompanionIntent(normalized: String, lower: String): GhostIntent? {
        if (lower.startsWith("laptop ") || lower.startsWith("desktop ") || lower.startsWith("pc ") || lower.startsWith("companion ")) {
            val commandPart = normalized.replace(Regex("^(laptop|desktop|pc|companion)\\s+", RegexOption.IGNORE_CASE), "").trim()
            val cmdLower = commandPart.lowercase(Locale.US)

            return when {
                cmdLower.startsWith("open ") -> {
                    val app = commandPart.substring(5).trim()
                    GhostIntent.CompanionCommand("open_app", app)
                }
                cmdLower.contains("volume up") -> GhostIntent.CompanionCommand("adjust_volume", "up")
                cmdLower.contains("volume down") -> GhostIntent.CompanionCommand("adjust_volume", "down")
                cmdLower.contains("mute") -> GhostIntent.CompanionCommand("adjust_volume", "mute")
                cmdLower.startsWith("search files ") -> {
                    val query = commandPart.substring(13).trim()
                    GhostIntent.CompanionCommand("search_files", query)
                }
                cmdLower.contains("lock") -> GhostIntent.CompanionCommand("lock", "")
                cmdLower.contains("status") -> GhostIntent.CompanionCommand("status", "")
                else -> GhostIntent.CompanionCommand("custom", commandPart)
            }
        }
        return null
    }

    private fun parseNavigationIntent(normalized: String, lower: String): GhostIntent? {
        if (lower == "go back" || lower == "back" || lower == "previous") {
            return GhostIntent.SystemNavigation(GhostIntent.NavAction.BACK)
        }
        if (lower == "go home" || lower == "home" || lower.contains("home screen")) {
            return GhostIntent.SystemNavigation(GhostIntent.NavAction.HOME)
        }
        if (lower.contains("recent apps") || lower.contains("recent tasks") || lower.contains("overview")) {
            return GhostIntent.SystemNavigation(GhostIntent.NavAction.RECENTS)
        }
        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ")) {
            val target = normalized.replace(Regex("^(click|tap|press)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (target.isNotBlank()) return GhostIntent.SystemNavigation(GhostIntent.NavAction.CLICK, target)
        }
        return null
    }
}
