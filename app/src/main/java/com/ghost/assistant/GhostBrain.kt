package com.ghost.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ghost.assistant.device.DeviceToggleManager
import com.ghost.assistant.features.AlarmClockManager
import com.ghost.assistant.features.CalendarManager
import com.ghost.assistant.features.CompanionManager
import com.ghost.assistant.features.WeatherManager
import com.ghost.assistant.media.MusicManager
import com.ghost.assistant.messaging.ContactMessagingManager
import com.ghost.assistant.notification.GhostNotificationListenerService
import com.ghost.assistant.router.GhostIntent
import com.ghost.assistant.router.IntentRouter
import com.ghost.assistant.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GhostBrain(
    private val context: Context,
    private val onStateChanged: (BrainState) -> Unit
) : TextToSpeech.OnInitListener, RecognitionListener {

    enum class BrainState {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING,
        ERROR
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val systemBridge = SystemBridge(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isTtsInitialized = false
    private var pendingSpeakText: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    var currentState: BrainState = BrainState.IDLE
        private set

    init {
        initTts()
        initRecognizer()
    }

    private fun updateState(newState: BrainState) {
        currentState = newState
        mainHandler.post {
            onStateChanged(newState)
        }
    }

    private fun getUserName(): String {
        return ThemeManager.getUserName(context)
    }

    private fun initTts() {
        mainHandler.post {
            try {
                tts = TextToSpeech(context, this)
            } catch (e: Exception) {
                Log.e("GhostBrain", "Failed to construct TextToSpeech: ${e.message}", e)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val defaultLocale = Locale.getDefault()
                val langResult = engine.setLanguage(defaultLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                engine.setPitch(0.9f)
                engine.setSpeechRate(1.0f)
                isTtsInitialized = true
                Log.i("GhostBrain", "TextToSpeech successfully initialized.")

                val pending = pendingSpeakText
                val onDoneCallback = pendingOnDone
                if (pending != null) {
                    pendingSpeakText = null
                    pendingOnDone = null
                    speak(pending, onDoneCallback)
                }
            }
        } else {
            Log.e("GhostBrain", "TextToSpeech init failed with status: $status")
            mainHandler.post {
                Toast.makeText(context, "TTS engine error ($status). Install Google Speech Services.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            updateState(BrainState.IDLE)
            onDone?.invoke()
            return
        }

        Log.i("GhostBrain", "Speaking: $text")
        mainHandler.post {
            Toast.makeText(context, "G.H.O.S.T.: $text", Toast.LENGTH_SHORT).show()
        }

        if (!isTtsInitialized) {
            Log.w("GhostBrain", "TTS not ready yet. Queuing text: $text")
            pendingSpeakText = text
            pendingOnDone = onDone
            updateState(BrainState.PROCESSING)
            return
        }

        updateState(BrainState.SPEAKING)
        val utteranceId = "ghost_utterance_${System.currentTimeMillis()}"

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                updateState(BrainState.SPEAKING)
            }

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    updateState(BrainState.IDLE)
                    if (onDone != null) {
                        mainHandler.post(onDone)
                    } else if (ThemeManager.isWakeWordEnabled(context)) {
                        mainHandler.postDelayed({
                            if (currentState == BrainState.IDLE) {
                                startListening(isWakeWordLoop = true)
                            }
                        }, 400)
                    }
                }
            }

            override fun onError(id: String?) {
                if (id == utteranceId) {
                    Log.e("GhostBrain", "TTS playback error for utterance: $id")
                    updateState(BrainState.IDLE)
                    onDone?.let { mainHandler.post(it) }
                }
            }
        })

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e("GhostBrain", "tts.speak returned error code: $result")
            updateState(BrainState.IDLE)
            onDone?.invoke()
        }
    }

    fun stopSpeaking() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }
        updateState(BrainState.IDLE)
    }

    private fun initRecognizer() {
        mainHandler.post {
            ensureSpeechRecognizer()
        }
    }

    private fun ensureSpeechRecognizer(): Boolean {
        if (speechRecognizer != null) return true

        return try {
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            if (recognizer != null) {
                recognizer.setRecognitionListener(this@GhostBrain)
                speechRecognizer = recognizer
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("GhostBrain", "Error instantiating SpeechRecognizer: ${e.message}", e)
            false
        }
    }

    fun startListening(isWakeWordLoop: Boolean = false) {
        mainHandler.post {
            if (currentState == BrainState.SPEAKING) {
                stopSpeaking()
                return@post
            }

            if (currentState == BrainState.LISTENING) {
                if (!isWakeWordLoop) {
                    cancelListening()
                }
                return@post
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                if (!isWakeWordLoop) {
                    val userName = getUserName()
                    Toast.makeText(context, "Microphone permission required, $userName.", Toast.LENGTH_LONG).show()
                }
                updateState(BrainState.ERROR)
                scope.launch {
                    delay(1500)
                    updateState(BrainState.IDLE)
                }
                return@post
            }

            if (!ensureSpeechRecognizer()) {
                if (!isWakeWordLoop) {
                    val userName = getUserName()
                    Toast.makeText(context, "Speech recognition engine unavailable, $userName.", Toast.LENGTH_LONG).show()
                }
                updateState(BrainState.ERROR)
                scope.launch {
                    delay(1500)
                    updateState(BrainState.IDLE)
                }
                return@post
            }

            if (!isWakeWordLoop) {
                vibrate(50)
                Toast.makeText(context, "J.A.R.V.I.S. listening...", Toast.LENGTH_SHORT).show()
            }
            updateState(BrainState.LISTENING)

            try {
                speechRecognizer?.cancel()

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e("GhostBrain", "Failed to start listening: ${e.message}", e)
                updateState(BrainState.ERROR)
                scope.launch {
                    delay(1500)
                    updateState(BrainState.IDLE)
                }
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w("GhostBrain", "stopListening error: ${e.message}")
            }
            updateState(BrainState.PROCESSING)
        }
    }

    fun cancelListening() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w("GhostBrain", "cancelListening error: ${e.message}")
            }
            updateState(BrainState.IDLE)
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w("GhostBrain", "destroyRecognizer error: ${e.message}")
        }
        speechRecognizer = null
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(ms)
                }
            }
        } catch (e: Exception) {
            Log.w("GhostBrain", "Haptic error: ${e.message}")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d("GhostBrain", "SpeechRecognizer: onReadyForSpeech")
    }

    override fun onBeginningOfSpeech() {
        Log.d("GhostBrain", "SpeechRecognizer: onBeginningOfSpeech")
        vibrate(30)
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d("GhostBrain", "SpeechRecognizer: onEndOfSpeech")
        updateState(BrainState.PROCESSING)
    }

    override fun onError(error: Int) {
        val userName = getUserName()
        val (errorMsg, spokenResponse) = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording failure" to "Microphone audio error, $userName."
            SpeechRecognizer.ERROR_CLIENT -> "Client internal error" to null
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions" to "Microphone permission is required, $userName."
            SpeechRecognizer.ERROR_NETWORK -> "Network failure" to "Network connection lost, $userName."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout" to "Speech recognition timed out, $userName."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized" to "I didn't catch that, $userName. Tap to retry."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy" to null
            SpeechRecognizer.ERROR_SERVER -> "Server error" to "Speech recognition server error, $userName."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected" to null
            else -> "Speech error ($error)" to null
        }
        Log.e("GhostBrain", "SpeechRecognizer error: $errorMsg ($error)")

        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            destroyRecognizer()
            ensureSpeechRecognizer()
        }

        // If continuous wake word mode is active, restart seamlessly without intrusive speech
        if (ThemeManager.isWakeWordEnabled(context) &&
            (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        ) {
            updateState(BrainState.IDLE)
            scope.launch {
                delay(300)
                if (currentState == BrainState.IDLE) {
                    startListening(isWakeWordLoop = true)
                }
            }
            return
        }

        updateState(BrainState.ERROR)
        mainHandler.post {
            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
        }

        scope.launch {
            if (spokenResponse != null) {
                speak(spokenResponse)
            } else {
                delay(1000)
                updateState(BrainState.IDLE)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val query = matches[0].trim()
            Log.i("GhostBrain", "Recognized voice query: $query")
            mainHandler.post {
                Toast.makeText(context, "\"$query\"", Toast.LENGTH_SHORT).show()
            }
            processIntent(query)
        } else {
            val userName = getUserName()
            speak("I didn't catch any command, $userName.")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            Log.d("GhostBrain", "Partial recognized: ${matches[0]}")
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    /**
     * Main Intent Routing and Execution Engine.
     * Maps voice input to actions using IntentRouter and delivers spoken feedback.
     */
    fun processIntent(rawQuery: String, onComplete: (() -> Unit)? = null) {
        updateState(BrainState.PROCESSING)
        val userName = getUserName()
        val intent = IntentRouter.routeIntent(rawQuery)

        scope.launch {
            when (intent) {
                // 1. Device Toggles (Bluetooth, Wifi, Hotspot, Mobile Data)
                is GhostIntent.DeviceToggle -> {
                    val result = DeviceToggleManager.executeToggle(context, intent.device, intent.action, userName)
                    val reply = when (result) {
                        is DeviceToggleManager.ToggleResult.DirectSuccess -> result.message
                        is DeviceToggleManager.ToggleResult.SettingsOpened -> result.message
                        is DeviceToggleManager.ToggleResult.Error -> result.message
                    }
                    speak(reply, onComplete)
                }

                // 2. Music Playback & Media Controls
                is GhostIntent.PlayMusic -> {
                    val reply = MusicManager.playFromSearch(context, intent.query ?: "", userName)
                    speak(reply, onComplete)
                }
                is GhostIntent.MediaControl -> {
                    val reply = MusicManager.controlMedia(context, intent.action, userName)
                    speak(reply, onComplete)
                }

                // 3. Messaging (SMS / WhatsApp)
                is GhostIntent.SendMessage -> {
                    val reply = ContactMessagingManager.sendMessage(
                        context,
                        intent.contact,
                        intent.messageText,
                        intent.preferWhatsApp,
                        userName
                    )
                    speak(reply, onComplete)
                }

                // 4. Phone Calls
                is GhostIntent.CallContact -> {
                    executeDirectCall(intent.target, userName, onComplete)
                }

                // 5. Flashlight / Torch
                is GhostIntent.Flashlight -> {
                    val reply = when (intent.action) {
                        DeviceToggleManager.ToggleAction.ON -> {
                            val ok = systemBridge.setTorchMode(true).isSuccess
                            if (ok) "Tactical illuminator engaged, $userName." else "Failed to engage illuminator, $userName."
                        }
                        DeviceToggleManager.ToggleAction.OFF -> {
                            val ok = systemBridge.setTorchMode(false).isSuccess
                            if (ok) "Tactical illuminator disengaged, $userName." else "Failed to disengage illuminator, $userName."
                        }
                        DeviceToggleManager.ToggleAction.TOGGLE -> {
                            val res = systemBridge.toggleTorch()
                            if (res.isSuccess) {
                                val state = if (res.getOrDefault(false)) "engaged" else "disengaged"
                                "Tactical illuminator $state, $userName."
                            } else {
                                "Unable to toggle illuminator, $userName."
                            }
                        }
                    }
                    speak(reply, onComplete)
                }

                // 6. Volume Control
                is GhostIntent.VolumeControl -> {
                    val reply = when (intent.action) {
                        GhostIntent.VolumeAction.UP -> {
                            systemBridge.adjustMediaVolume(true)
                            "Media volume increased, $userName."
                        }
                        GhostIntent.VolumeAction.DOWN -> {
                            systemBridge.adjustMediaVolume(false)
                            "Media volume decreased, $userName."
                        }
                        GhostIntent.VolumeAction.MUTE -> {
                            systemBridge.setMediaVolumeMute(true)
                            "Audio muted, $userName."
                        }
                        GhostIntent.VolumeAction.UNMUTE -> {
                            systemBridge.setMediaVolumeMute(false)
                            "Audio unmuted, $userName."
                        }
                        GhostIntent.VolumeAction.SET_PERCENT -> {
                            val pct = intent.percent ?: 50
                            systemBridge.setMediaVolumePercent(pct)
                            "Media volume set to $pct percent, $userName."
                        }
                    }
                    speak(reply, onComplete)
                }

                // 7. Brightness Control
                is GhostIntent.BrightnessControl -> {
                    val reply = when (intent.action) {
                        GhostIntent.BrightnessAction.UP -> systemBridge.adjustBrightnessRelative(true, userName)
                        GhostIntent.BrightnessAction.DOWN -> systemBridge.adjustBrightnessRelative(false, userName)
                        GhostIntent.BrightnessAction.MAX -> systemBridge.setBrightnessPercent(100, userName)
                        GhostIntent.BrightnessAction.MIN -> systemBridge.setBrightnessPercent(10, userName)
                        GhostIntent.BrightnessAction.SET_PERCENT -> systemBridge.setBrightnessPercent(intent.percent ?: 50, userName)
                    }
                    speak(reply, onComplete)
                }

                // 8. Open Apps by Name
                is GhostIntent.OpenApp -> {
                    launchApplicationByName(intent.appName, userName, onComplete)
                }

                // 9. Alarms, Timers, Reminders
                is GhostIntent.SetAlarm -> {
                    val reply = AlarmClockManager.setAlarm(context, intent.hour, intent.minute, intent.label, userName)
                    speak(reply, onComplete)
                }
                is GhostIntent.SetTimer -> {
                    val reply = AlarmClockManager.setTimer(context, intent.seconds, intent.label, userName)
                    speak(reply, onComplete)
                }
                is GhostIntent.SetReminder -> {
                    val reply = AlarmClockManager.setReminder(context, intent.text, userName)
                    speak(reply, onComplete)
                }

                // 10. Calendar Events
                is GhostIntent.ReadCalendar -> {
                    val reply = CalendarManager.getTodayEventsReadout(context, userName)
                    speak(reply, onComplete)
                }

                // 11. Read & Reply Notifications
                is GhostIntent.ReadNotifications -> {
                    val reply = GhostNotificationListenerService.getRecentNotificationsSummary(context, userName)
                    speak(reply, onComplete)
                }
                is GhostIntent.ReplyNotification -> {
                    val reply = GhostNotificationListenerService.replyToLatestNotification(context, intent.text, userName)
                    speak(reply, onComplete)
                }

                // 12. Battery Status
                is GhostIntent.BatteryStatus -> {
                    val battery = systemBridge.getBatteryStatus()
                    val chargingText = if (battery.isCharging) "charging" else "discharging"
                    val reply = "Power cell at ${battery.percentage} percent, $userName. Status: $chargingText. Temperature reading: ${battery.temperatureCelsius} degrees Celsius."
                    speak(reply, onComplete)
                }

                // 13. Weather Lookup
                is GhostIntent.Weather -> {
                    val reply = WeatherManager.getWeatherSummary(intent.location, userName)
                    speak(reply, onComplete)
                }

                // 14. Screenshot & Lock Screen
                is GhostIntent.TakeScreenshot -> {
                    val handled = GhostAccessibilityService.instance?.performGlobalScreenshot() ?: false
                    val reply = if (handled) "Capturing screen for you, $userName." else "Accessibility node required to capture screen, $userName."
                    speak(reply, onComplete)
                }
                is GhostIntent.LockScreen -> {
                    val handled = GhostAccessibilityService.instance?.performGlobalLock() ?: false
                    val reply = if (handled) "Workstation locked, $userName." else "Accessibility node required to lock screen, $userName."
                    speak(reply, onComplete)
                }

                // 15. Laptop Companion Mode
                is GhostIntent.CompanionCommand -> {
                    val reply = CompanionManager.executeCompanionDirective(context, intent.command, intent.arg, userName)
                    speak(reply, onComplete)
                }

                // 16. Accessibility Navigation
                is GhostIntent.SystemNavigation -> {
                    val service = GhostAccessibilityService.instance
                    val reply = when (intent.action) {
                        GhostIntent.NavAction.BACK -> {
                            if (service?.performGlobalBack() == true) "Navigating back, $userName." else "Accessibility service inactive, $userName."
                        }
                        GhostIntent.NavAction.HOME -> {
                            if (service?.performGlobalHome() == true) "Returning home, $userName." else "Accessibility service inactive, $userName."
                        }
                        GhostIntent.NavAction.RECENTS -> {
                            if (service?.performGlobalRecents() == true) "Displaying recent tasks, $userName." else "Accessibility service inactive, $userName."
                        }
                        GhostIntent.NavAction.NOTIFICATIONS -> {
                            if (service?.performGlobalNotifications() == true) "Opening notifications, $userName." else "Accessibility service inactive, $userName."
                        }
                        GhostIntent.NavAction.CLICK -> {
                            val target = intent.targetText ?: ""
                            if (service?.clickElementByText(target) == true) "Clicked on $target, $userName." else "Unable to find $target on screen, $userName."
                        }
                    }
                    speak(reply, onComplete)
                }

                // 17. Time & Date
                is GhostIntent.DateTime -> {
                    val reply = if (intent.isTime) {
                        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                        "The time is $timeStr, $userName."
                    } else {
                        val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                        "Today is $dateStr, $userName."
                    }
                    speak(reply, onComplete)
                }

                // 18. System Info & Greetings
                is GhostIntent.SystemStatus -> {
                    val battery = systemBridge.getBatteryStatus()
                    val accessOk = if (GhostAccessibilityService.isRunning) "active" else "offline"
                    val wakeWordOk = if (ThemeManager.isWakeWordEnabled(context)) "active" else "standby"
                    speak("System diagnostics nominal, $userName. Battery: ${battery.percentage}%. Accessibility: $accessOk. Wake Word: $wakeWordOk.", onComplete)
                }
                is GhostIntent.Identity -> {
                    speak("I am J.A.R.V.I.S., operational within the G.H.O.S.T. mobile terminal. Your personal tactical assistant, $userName.", onComplete)
                }
                is GhostIntent.Help -> {
                    speak("I can toggle Wi-Fi, Bluetooth, hotspot, mobile data, play music, send messages, place calls, set alarms, check the weather, control volume and brightness, launch apps, and control your laptop companion, $userName.", onComplete)
                }
                is GhostIntent.Greeting -> {
                    speak("G.H.O.S.T. systems online and ready for your commands, $userName.", onComplete)
                }

                // 19. Web Search
                is GhostIntent.WebSearch -> {
                    val brief = withContext(Dispatchers.IO) {
                        queryWikipediaOrDuckDuckGo(intent.query, userName)
                    }
                    speak(brief, onComplete)
                }

                // 20. Clear spoken fallback for unrecognized commands ("no action found" issue)
                is GhostIntent.Unknown -> {
                    speak("I didn't catch a command for that, $userName. Say 'help' to hear available commands.", onComplete)
                }
            }
        }
    }

    private fun executeDirectCall(phoneNumberOrName: String, userName: String, onDone: (() -> Unit)?) {
        val resolved = ContactMessagingManager.resolveContact(context, phoneNumberOrName)
        val targetNumber = resolved?.phoneNumber ?: phoneNumberOrName.replace(Regex("[^0-9+]"), "")

        if (targetNumber.isBlank()) {
            speak("I was unable to locate $phoneNumberOrName in your contacts, $userName.", onDone)
            return
        }

        val displayName = resolved?.displayName ?: targetNumber

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$targetNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(callIntent)
                speak("Initiating call to $displayName, $userName.", onDone)
                return
            } catch (_: Exception) {}
        }

        // Fallback to dialer
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$targetNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(dialIntent)
            speak("Opening dialer for $displayName, $userName.", onDone)
        } catch (e: Exception) {
            speak("Unable to place call to $displayName, $userName.", onDone)
        }
    }

    private fun launchApplicationByName(name: String, userName: String, onDone: (() -> Unit)?) {
        val pm = context.packageManager
        val cleanName = name.lowercase(Locale.US).trim()

        // 1. Chrome & Browser
        if (cleanName.contains("chrome") || cleanName.contains("browser") || cleanName.contains("internet") || cleanName == "web") {
            val chromeLaunch = pm.getLaunchIntentForPackage("com.android.chrome")
            if (chromeLaunch != null) {
                chromeLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chromeLaunch)
                speak("Opening Chrome for you, $userName.", onDone)
                return
            }

            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (browserIntent.resolveActivity(pm) != null) {
                context.startActivity(browserIntent)
                speak("Opening web browser for you, $userName.", onDone)
                return
            }
        }

        // 2. App aliases
        val aliasMap = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "maps" to "com.google.android.apps.maps",
            "settings" to "com.android.settings",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "store" to "com.android.vending",
            "telegram" to "org.telegram.messenger",
            "spotify" to "com.spotify.music",
            "calculator" to "com.google.android.calculator",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "clock" to "com.google.android.deskclock",
            "termux" to "com.termux",
            "terminal" to "com.termux"
        )

        val targetPkg = aliasMap[cleanName]
        if (targetPkg != null) {
            val intent = pm.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                speak("Opening $name for you, $userName.", onDone)
                return
            }
        }

        // 3. Fallback standard intents
        when (cleanName) {
            "camera" -> {
                val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (camIntent.resolveActivity(pm) != null) {
                    context.startActivity(camIntent)
                    speak("Opening camera, $userName.", onDone)
                    return
                }
            }
            "settings" -> {
                val setIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(setIntent)
                speak("Opening settings, $userName.", onDone)
                return
            }
            "phone", "dialer" -> {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                speak("Opening dialer, $userName.", onDone)
                return
            }
            "clock", "alarm" -> {
                val clockIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (clockIntent.resolveActivity(pm) != null) {
                    context.startActivity(clockIntent)
                    speak("Opening alarms, $userName.", onDone)
                    return
                }
            }
        }

        // 4. Query installed launcher apps
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveList = pm.queryIntentActivities(launcherIntent, 0)
            val matchedActivity = resolveList.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().lowercase(Locale.US)
                val pkg = info.activityInfo.packageName.lowercase(Locale.US)
                label == cleanName || label.contains(cleanName) || cleanName.contains(label) || pkg.contains(cleanName)
            }

            if (matchedActivity != null) {
                val launchIntent = pm.getLaunchIntentForPackage(matchedActivity.activityInfo.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    speak("Opening ${matchedActivity.loadLabel(pm)} for you, $userName.", onDone)
                    return
                }
            }
        } catch (e: Exception) {
            Log.w("GhostBrain", "Error searching launcher apps: ${e.message}")
        }

        speak("I was unable to find $name on this device, $userName.", onDone)
    }

    private fun queryWikipediaOrDuckDuckGo(query: String, userName: String): String {
        val cleanQuery = query.replace(Regex("^(search for|search|lookup|who is|what is|tell me about|define)\\s+", RegexOption.IGNORE_CASE), "").trim()

        // Wikipedia REST API
        try {
            val encodedWiki = URLEncoder.encode(cleanQuery.replace(" ", "_"), "UTF-8")
            val wikiUrl = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encodedWiki")
            val conn = wikiUrl.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "GHOST-Assistant/1.0 (Android; Linux)")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val stream = conn.inputStream
                val response = BufferedReader(InputStreamReader(stream)).readText()
                val json = JSONObject(response)
                val extract = json.optString("extract")
                if (extract.isNotBlank()) {
                    val firstSentence = extract.split(". ").firstOrNull()?.let { if (!it.endsWith(".")) "$it." else it }
                    if (!firstSentence.isNullOrBlank()) {
                        return firstSentence
                    }
                }
            }
        } catch (_: Exception) {}

        // DuckDuckGo API
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; Linux)")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200 || conn.responseCode == 202) {
                val stream = conn.inputStream
                val response = BufferedReader(InputStreamReader(stream)).readText()
                val json = JSONObject(response)
                val abstractText = json.optString("AbstractText")
                if (abstractText.isNotBlank()) {
                    return abstractText.split(". ").firstOrNull()?.let { if (!it.endsWith(".")) "$it." else it } ?: abstractText
                }
                val answer = json.optString("Answer")
                if (answer.isNotBlank()) {
                    return answer
                }
            }
        } catch (_: Exception) {}

        return "No immediate tactical intelligence found for $query, $userName."
    }

    fun destroy() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
        destroyRecognizer()
    }
}
