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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private fun initTts() {
        mainHandler.post {
            try {
                tts = TextToSpeech(context, this)
            } catch (e: Exception) {
                Log.e("GhostBrain", "Failed to instantiate TextToSpeech: ${e.message}", e)
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

                // Speak any queued announcement
                val pending = pendingSpeakText
                if (pending != null) {
                    pendingSpeakText = null
                    speak(pending)
                }
            }
        } else {
            Log.e("GhostBrain", "TextToSpeech initialization failed with code: $status")
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
                    onDone?.let { mainHandler.post(it) }
                }
            }

            override fun onError(id: String?) {
                if (id == utteranceId) {
                    Log.e("GhostBrain", "TTS utterance playback error on ID: $id")
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

    fun startListening() {
        mainHandler.post {
            // If already speaking, stop playback
            if (currentState == BrainState.SPEAKING) {
                stopSpeaking()
                return@post
            }

            // If already listening, cancel session
            if (currentState == BrainState.LISTENING) {
                cancelListening()
                return@post
            }

            // Check microphone permission
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Microphone permission required! Grant in Settings.", Toast.LENGTH_LONG).show()
                updateState(BrainState.ERROR)
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    updateState(BrainState.IDLE)
                }
                return@post
            }

            if (!ensureSpeechRecognizer()) {
                Toast.makeText(context, "Speech recognition service unavailable on device.", Toast.LENGTH_LONG).show()
                updateState(BrainState.ERROR)
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    updateState(BrainState.IDLE)
                }
                return@post
            }

            vibrate(50)
            updateState(BrainState.LISTENING)
            Toast.makeText(context, "G.H.O.S.T. listening...", Toast.LENGTH_SHORT).show()

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
                    kotlinx.coroutines.delay(1500)
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
        val (errorMsg, spokenResponse) = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording failure" to "Microphone audio error."
            SpeechRecognizer.ERROR_CLIENT -> "Client internal error" to null
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions" to "Microphone permission required."
            SpeechRecognizer.ERROR_NETWORK -> "Network failure" to "Network connection lost."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout" to "Speech recognition timed out."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized" to "I didn't catch that, Commander."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy" to null
            SpeechRecognizer.ERROR_SERVER -> "Server error" to "Speech recognition server error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected" to "No speech detected."
            else -> "Speech error ($error)" to null
        }
        Log.e("GhostBrain", "SpeechRecognizer error: $errorMsg ($error)")

        // Recreate recognizer if client or busy state error
        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            destroyRecognizer()
            ensureSpeechRecognizer()
        }

        updateState(BrainState.ERROR)
        mainHandler.post {
            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
        }

        scope.launch {
            if (spokenResponse != null) {
                speak(spokenResponse)
            } else {
                kotlinx.coroutines.delay(1200)
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
            speak("I didn't catch any command.")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            Log.d("GhostBrain", "Partial recognized: ${matches[0]}")
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    fun processIntent(rawQuery: String) {
        updateState(BrainState.PROCESSING)
        val command = rawQuery.trim().lowercase(Locale.US)

        when {
            // System Greeting & Identity
            command.matches(Regex("^(hello|hi|hey|ghost|wake up|systems? online).*")) -> {
                speak("G.H.O.S.T. systems online and awaiting your orders.")
            }
            command.contains("who are you") || command.contains("what are you") || command.contains("your name") || command.contains("identify yourself") -> {
                speak("I am G.H.O.S.T., General Hardware and Operative System Tracker. Tactical assistant deployed.")
            }
            command.contains("what can you do") || command == "help" || command.contains("commands") -> {
                speak("I can toggle the flashlight, report battery, launch apps, navigate screens, adjust volume, place calls, and query tactical intelligence.")
            }
            command.contains("status report") || command.contains("system status") || command.contains("how are you") -> {
                val battery = systemBridge.getBatteryStatus()
                val accessibility = if (GhostAccessibilityService.isRunning) "operational" else "offline"
                speak("Tactical status: Battery is at ${battery.percentage} percent. Accessibility node engine is $accessibility.")
            }

            // Current Time & Date
            command.contains("what time") || command == "time" || command.contains("current time") -> {
                val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                speak("The current time is $timeStr.")
            }
            command.contains("what date") || command == "date" || command.contains("today's date") || command.contains("what day") -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                speak("Today is $dateStr.")
            }

            // Hardware: Flashlight / Torch ON
            command.contains("torch on") || command.contains("flashlight on") ||
            command.contains("turn on torch") || command.contains("turn on flashlight") ||
            command.contains("turn on the torch") || command.contains("turn on the flashlight") ||
            command.contains("enable torch") || command.contains("light on") -> {
                val res = systemBridge.setTorchMode(true)
                if (res.isSuccess) {
                    speak("Tactical illuminator engaged.")
                } else {
                    speak("Failed to engage illuminator.")
                }
            }

            // Hardware: Flashlight / Torch OFF
            command.contains("torch off") || command.contains("flashlight off") ||
            command.contains("turn off torch") || command.contains("turn off flashlight") ||
            command.contains("turn off the torch") || command.contains("turn off the flashlight") ||
            command.contains("disable torch") || command.contains("light off") -> {
                val res = systemBridge.setTorchMode(false)
                if (res.isSuccess) {
                    speak("Tactical illuminator disengaged.")
                } else {
                    speak("Failed to disengage illuminator.")
                }
            }

            // Hardware: Flashlight Toggle
            command == "torch" || command == "flashlight" || command.contains("toggle torch") || command.contains("toggle flashlight") -> {
                val res = systemBridge.toggleTorch()
                if (res.isSuccess) {
                    val state = if (res.getOrDefault(false)) "engaged" else "disengaged"
                    speak("Tactical illuminator $state.")
                } else {
                    speak("Unable to toggle illuminator.")
                }
            }

            // Hardware: Battery Status
            command.contains("battery") || command.contains("power status") || command.contains("charge level") -> {
                val battery = systemBridge.getBatteryStatus()
                val chargingText = if (battery.isCharging) "charging" else "discharging"
                speak("Power cell at ${battery.percentage} percent. Status: $chargingText. Thermal reading: ${battery.temperatureCelsius} degrees Celsius.")
            }

            // Audio Volume Control
            command.contains("volume up") || command.contains("increase volume") || command.contains("louder") || command.contains("raise volume") -> {
                systemBridge.adjustMediaVolume(true)
                speak("Media volume increased.")
            }
            command.contains("volume down") || command.contains("decrease volume") || command.contains("lower volume") || command.contains("quieter") -> {
                systemBridge.adjustMediaVolume(false)
                speak("Media volume decreased.")
            }

            // Settings Shortcuts
            command.contains("open wifi") || command.contains("wifi settings") || command == "wifi" || command == "wi-fi" -> {
                systemBridge.openWifiSettings()
                speak("Accessing Wi-Fi settings.")
            }
            command.contains("open bluetooth") || command.contains("bluetooth settings") || command == "bluetooth" -> {
                systemBridge.openBluetoothSettings()
                speak("Accessing Bluetooth settings.")
            }

            // Accessibility: Navigation
            command == "go back" || command == "back" || command == "previous" -> {
                val handled = GhostAccessibilityService.instance?.performGlobalBack() ?: false
                if (handled) {
                    speak("Navigating back.")
                } else {
                    speak("Accessibility service inactive.")
                }
            }
            command == "go home" || command == "home" || command.contains("home screen") -> {
                val handled = GhostAccessibilityService.instance?.performGlobalHome() ?: false
                if (handled) {
                    speak("Returning to home screen.")
                } else {
                    speak("Accessibility service inactive.")
                }
            }
            command.contains("recent") || command.contains("switch app") || command.contains("overview") -> {
                val handled = GhostAccessibilityService.instance?.performGlobalRecents() ?: false
                if (handled) {
                    speak("Displaying recent tasks.")
                } else {
                    speak("Accessibility service inactive.")
                }
            }
            command.contains("notification") -> {
                val handled = GhostAccessibilityService.instance?.performGlobalNotifications() ?: false
                if (handled) {
                    speak("Opening notifications.")
                } else {
                    speak("Accessibility service inactive.")
                }
            }
            command.contains("screenshot") || command.contains("capture screen") -> {
                val handled = GhostAccessibilityService.instance?.performGlobalScreenshot() ?: false
                if (handled) {
                    speak("Capturing screen.")
                } else {
                    speak("Accessibility service unavailable or unsupported on this Android version.")
                }
            }
            command.contains("lock screen") || command.contains("lock phone") || command.contains("lock device") -> {
                val handled = GhostAccessibilityService.instance?.performGlobalLock() ?: false
                if (handled) {
                    speak("Terminal locked.")
                } else {
                    speak("Accessibility service unavailable or unsupported on this Android version.")
                }
            }

            // Accessibility: Click by label
            command.startsWith("click ") || command.startsWith("press ") || command.startsWith("tap ") -> {
                val targetLabel = rawQuery.replace(Regex("^(click|press|tap)\\s+", RegexOption.IGNORE_CASE), "").trim()
                val service = GhostAccessibilityService.instance
                if (service != null) {
                    val clicked = service.clickElementByText(targetLabel)
                    if (clicked) {
                        speak("Target clicked.")
                    } else {
                        speak("Unable to locate element $targetLabel.")
                    }
                } else {
                    speak("Accessibility node engine is unbound.")
                }
            }

            // App Launching
            command.startsWith("open ") || command.startsWith("launch ") || command.startsWith("start ") -> {
                val appTarget = rawQuery.replace(Regex("^(open|launch|start)\\s+", RegexOption.IGNORE_CASE), "").trim()
                launchApplicationByName(appTarget)
            }

            // Direct Cellular Call
            command.startsWith("call ") || command.startsWith("dial ") -> {
                val targetNumber = rawQuery.replace(Regex("^(call|dial)\\s+", RegexOption.IGNORE_CASE), "").trim()
                executeDirectCall(targetNumber)
            }

            // Direct SMS Routing
            command.startsWith("text ") || command.startsWith("message ") || command.startsWith("sms ") -> {
                executeSmsRouting(rawQuery)
            }

            // Fallback: Web Intelligence / Knowledge Engine
            else -> {
                resolveWebQuery(rawQuery)
            }
        }
    }

    private fun launchApplicationByName(name: String) {
        val pm = context.packageManager
        val cleanName = name.lowercase(Locale.US).trim()

        val aliasMap = mapOf(
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "clock" to "com.google.android.deskclock",
            "play store" to "com.android.vending",
            "store" to "com.android.vending"
        )

        val targetPkg = aliasMap[cleanName]
        if (targetPkg != null) {
            val intent = pm.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                speak("Deploying $name.")
                return
            }
        }

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = installedApps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase(Locale.US)
            label == cleanName || label.contains(cleanName) || app.packageName.lowercase(Locale.US).contains(cleanName)
        }

        if (match != null) {
            val service = GhostAccessibilityService.instance
            val launched = service?.launchApp(match.packageName)
                ?: run {
                    val intent = pm.getLaunchIntentForPackage(match.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        context.startActivity(intent)
                        true
                    } else false
                }

            if (launched) {
                speak("Deploying ${pm.getApplicationLabel(match)}.")
            } else {
                speak("Failed to deploy package.")
            }
        } else {
            speak("Application $name not identified on host.")
        }
    }

    private fun executeDirectCall(phoneNumber: String) {
        val sanitizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (sanitizedNumber.isEmpty()) {
            speak("Invalid phone sequence.")
            return
        }
        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$sanitizedNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(callIntent)
            speak("Initiating cellular link to $sanitizedNumber.")
        } catch (e: SecurityException) {
            speak("Permission denied for direct cellular dial.")
        }
    }

    private fun executeSmsRouting(command: String) {
        val parts = command.split(" ", limit = 3)
        if (parts.size < 3) {
            speak("Incomplete SMS format. State: text, number, message.")
            return
        }
        val targetNumber = parts[1]
        val message = parts[2]

        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$targetNumber")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(smsIntent)
            speak("Dispatching message payload to $targetNumber.")
        } catch (e: Exception) {
            speak("SMS transmission failure.")
        }
    }

    private fun resolveWebQuery(query: String) {
        scope.launch {
            val brief = withContext(Dispatchers.IO) {
                queryWikipediaOrDuckDuckGo(query)
            }
            speak(brief)
        }
    }

    private fun queryWikipediaOrDuckDuckGo(query: String): String {
        val cleanQuery = query.replace(Regex("^(search for|search|lookup|who is|what is|tell me about|define)\\s+", RegexOption.IGNORE_CASE), "").trim()

        // 1. Try Wikipedia REST API summary
        try {
            val encodedWiki = URLEncoder.encode(cleanQuery.replace(" ", "_"), "UTF-8")
            val wikiUrl = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encodedWiki")
            val conn = wikiUrl.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "GHOST-Assistant/1.0 (Android; Linux)")
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
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
        } catch (e: Exception) {
            Log.w("GhostBrain", "Wikipedia lookup exception: ${e.message}")
        }

        // 2. Try DuckDuckGo Instant Answer API
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; Linux)")
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.requestMethod = "GET"

            val code = conn.responseCode
            if (code == 200 || code == 202) {
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
        } catch (e: Exception) {
            Log.w("GhostBrain", "DuckDuckGo lookup exception: ${e.message}")
        }

        return "No tactical intelligence found for $query."
    }

    fun destroy() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
        destroyRecognizer()
    }
}
