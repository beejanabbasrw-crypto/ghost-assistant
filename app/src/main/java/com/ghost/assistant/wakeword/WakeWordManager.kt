package com.ghost.assistant.wakeword

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class WakeWordManager(
    private val context: Context,
    private val onWakeWordDetected: (query: String?) -> Unit,
    private val onListeningStateChanged: ((Boolean) -> Unit)? = null
) : RecognitionListener {

    companion object {
        private const val TAG = "GhostWakeWord"
        private const val WATCHDOG_TIMEOUT_MS = 10000L // 10 seconds timeout before force-restarting
        private const val RESTART_DELAY_MS = 250L
        private const val ERROR_RETRY_DELAY_MS = 600L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val isRunning = AtomicBoolean(false)
    private val isPausedForTts = AtomicBoolean(false)
    private var isActivelyListening = false

    private val watchdogRunnable = Runnable {
        if (isRunning.get() && !isPausedForTts.get()) {
            Log.w(TAG, "Watchdog timeout: SpeechRecognizer did not report back. Force recreating.")
            restartListening(cleanRecreate = true)
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Starting persistent WakeWordManager.")
        ensureRecognizer()
        startListeningInternal()
    }

    fun stop() {
        Log.i(TAG, "Stopping WakeWordManager.")
        isRunning.set(false)
        cancelWatchdog()
        destroyRecognizer()
        isActivelyListening = false
        onListeningStateChanged?.invoke(false)
    }

    fun pauseForTts() {
        isPausedForTts.set(true)
        cancelWatchdog()
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            isActivelyListening = false
            onListeningStateChanged?.invoke(false)
        }
    }

    fun resumeAfterTts() {
        isPausedForTts.set(false)
        if (isRunning.get()) {
            mainHandler.postDelayed({
                if (isRunning.get() && !isPausedForTts.get()) {
                    startListeningInternal()
                }
            }, 300)
        }
    }

    private fun ensureRecognizer(): Boolean {
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
                recognizer.setRecognitionListener(this)
                speechRecognizer = recognizer
                true
            } else {
                Log.e(TAG, "Failed to instantiate SpeechRecognizer: system returned null.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating SpeechRecognizer: ${e.message}", e)
            false
        }
    }

    private fun startListeningInternal() {
        mainHandler.post {
            if (!isRunning.get() || isPausedForTts.get()) return@post

            // Validate RECORD_AUDIO permission
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "RECORD_AUDIO permission is not granted. Cannot start wake word listener.")
                isActivelyListening = false
                onListeningStateChanged?.invoke(false)
                return@post
            }

            if (!ensureRecognizer()) {
                scheduleRestart(ERROR_RETRY_DELAY_MS, cleanRecreate = true)
                return@post
            }

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
                isActivelyListening = true
                onListeningStateChanged?.invoke(true)
                resetWatchdog()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recognizer: ${e.message}", e)
                scheduleRestart(ERROR_RETRY_DELAY_MS, cleanRecreate = true)
            }
        }
    }

    private fun restartListening(cleanRecreate: Boolean = false) {
        if (!isRunning.get() || isPausedForTts.get()) return

        mainHandler.post {
            if (cleanRecreate) {
                destroyRecognizer()
                ensureRecognizer()
            } else {
                try {
                    speechRecognizer?.cancel()
                } catch (_: Exception) {}
            }
            startListeningInternal()
        }
    }

    private fun scheduleRestart(delayMs: Long, cleanRecreate: Boolean = false) {
        cancelWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isRunning.get() && !isPausedForTts.get()) {
                restartListening(cleanRecreate)
            }
        }, delayMs)
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun resetWatchdog() {
        cancelWatchdog()
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    // --- RecognitionListener Callbacks ---

    override fun onReadyForSpeech(params: Bundle?) {
        resetWatchdog()
    }

    override fun onBeginningOfSpeech() {
        resetWatchdog()
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        cancelWatchdog()
    }

    override fun onError(error: Int) {
        cancelWatchdog()
        isActivelyListening = false
        onListeningStateChanged?.invoke(false)

        val isFatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        if (isFatal) {
            Log.e(TAG, "Fatal wake word error: INSUFFICIENT_PERMISSIONS.")
            stop()
            return
        }

        val needsRecreate = error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_AUDIO

        val delay = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RESTART_DELAY_MS
            else -> ERROR_RETRY_DELAY_MS
        }

        Log.d(TAG, "SpeechRecognizer error code: $error. Restarting in ${delay}ms (recreate=$needsRecreate)")
        scheduleRestart(delay, cleanRecreate = needsRecreate)
    }

    override fun onResults(results: Bundle?) {
        cancelWatchdog()
        isActivelyListening = false
        onListeningStateChanged?.invoke(false)

        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val detectedSpeech = matches[0].trim()
            Log.i(TAG, "Speech detected: \"$detectedSpeech\"")

            if (isWakeWordMatch(detectedSpeech)) {
                Log.i(TAG, "WAKE WORD TRIGGERED: \"$detectedSpeech\"")
                onWakeWordDetected(detectedSpeech)
                return
            }
        }

        // Seamless continuous loop
        scheduleRestart(RESTART_DELAY_MS, cleanRecreate = false)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        resetWatchdog()
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val partial = matches[0].trim()
            if (isWakeWordMatch(partial)) {
                Log.i(TAG, "WAKE WORD TRIGGERED via partial: \"$partial\"")
                cancelWatchdog()
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {}
                onWakeWordDetected(partial)
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun isWakeWordMatch(phrase: String): Boolean {
        val lower = phrase.lowercase(Locale.US)
        // Matches "ghost", "hey ghost", "ok ghost", "yo ghost", "jarvis", or commands starting with "ghost"
        return lower.contains("ghost") ||
                lower.contains("hey ghost") ||
                lower.contains("ok ghost") ||
                lower.contains("okay ghost") ||
                lower.contains("jarvis") ||
                lower.contains("hey jarvis")
    }
}
