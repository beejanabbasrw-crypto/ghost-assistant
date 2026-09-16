package com.ghost.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isTtsInitialized = false

    init {
        tts = TextToSpeech(context, this)
        initRecognizer()
    }

    private fun initRecognizer() {
        Handler(Looper.getMainLooper()).post {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(this@GhostBrain)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                engine.language = Locale.US
                engine.setPitch(0.85f)
                engine.setSpeechRate(1.05f)
                isTtsInitialized = true
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        onStateChanged(BrainState.SPEAKING)
        if (!isTtsInitialized) return

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ghost_utterance_id")
        scope.launch {
            while (tts?.isSpeaking == true) {
                kotlinx.coroutines.delay(100)
            }
            onStateChanged(BrainState.IDLE)
            onDone?.invoke()
        }
    }

    fun startListening() {
        onStateChanged(BrainState.LISTENING)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        onStateChanged(BrainState.IDLE)
    }

    override fun onResults(results: Bundle?) {
        onStateChanged(BrainState.PROCESSING)
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val command = matches[0]
            processIntent(command)
        } else {
            onStateChanged(BrainState.IDLE)
        }
    }

    private fun processIntent(rawQuery: String) {
        val command = rawQuery.trim().lowercase(Locale.US)

        when {
            // App launching
            command.startsWith("open ") || command.startsWith("launch ") -> {
                val appTarget = command.substringAfter(" ").trim()
                launchApplicationByName(appTarget)
            }

            // Hardware: Torch
            command.contains("torch on") || command.contains("flashlight on") -> {
                systemBridge.setTorchMode(true)
                speak("Tactical light active.")
            }
            command.contains("torch off") || command.contains("flashlight off") -> {
                systemBridge.setTorchMode(false)
                speak("Tactical light disengaged.")
            }

            // Hardware: Battery status
            command.contains("battery") || command.contains("power status") -> {
                val battery = systemBridge.getBatteryStatus()
                val chargingText = if (battery.isCharging) "charging" else "discharging"
                val speech = "Battery at ${battery.percentage} percent. State is $chargingText. Temperature ${battery.temperatureCelsius} degrees."
                speak(speech)
            }

            // Direct Call
            command.startsWith("call ") -> {
                val targetNumber = command.substringAfter("call ").trim()
                executeDirectCall(targetNumber)
            }

            // Direct SMS
            command.startsWith("text ") || command.startsWith("message ") -> {
                executeSmsRouting(command)
            }

            // Accessibility: Navigation
            command == "go back" || command == "back" -> {
                val handled = GhostAccessibilityService.instance?.performGlobalBack() ?: false
                if (!handled) speak("Accessibility service inactive.")
                onStateChanged(BrainState.IDLE)
            }
            command == "go home" || command == "home" -> {
                val handled = GhostAccessibilityService.instance?.performGlobalHome() ?: false
                if (!handled) speak("Accessibility service inactive.")
                onStateChanged(BrainState.IDLE)
            }

            // Accessibility: Click by label
            command.startsWith("click ") -> {
                val targetLabel = rawQuery.substringAfter("click ", "").trim()
                val service = GhostAccessibilityService.instance
                if (service != null) {
                    val clicked = service.clickElementByText(targetLabel)
                    if (clicked) {
                        speak("Target clicked.")
                    } else {
                        speak("Unable to locate element $targetLabel.")
                    }
                } else {
                    speak("Accessibility service unavailable.")
                }
            }

            // Fallback: Web search intelligence
            else -> {
                resolveWebQuery(rawQuery)
            }
        }
    }

    private fun launchApplicationByName(name: String) {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = installedApps.firstOrNull { app ->
            pm.getApplicationLabel(app).toString().lowercase(Locale.US).contains(name)
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
                speak("Executing ${pm.getApplicationLabel(match)}.")
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
            try {
                val brief = withContext(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.requestMethod = "GET"

                    if (conn.responseCode == 200) {
                        val stream = conn.inputStream
                        val response = BufferedReader(InputStreamReader(stream)).readText()
                        val json = JSONObject(response)
                        val abstractText = json.optString("AbstractText")
                        if (abstractText.isNotBlank()) {
                            abstractText.split(".").firstOrNull()?.plus(".") ?: "Query matched without summary."
                        } else {
                            "No immediate intelligence found for $query."
                        }
                    } else {
                        "Intelligence endpoint unreachable."
                    }
                }
                speak(brief)
            } catch (e: Exception) {
                speak("Search engine query timed out.")
            }
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {
        onStateChanged(BrainState.ERROR)
        scope.launch {
            kotlinx.coroutines.delay(1000)
            onStateChanged(BrainState.IDLE)
        }
    }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
