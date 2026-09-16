package com.ghost.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var toggleHudButton: Button
    private var testTts: TextToSpeech? = null

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "All runtime capabilities authorized.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some hardware permissions denied. Voice capabilities may degrade.", Toast.LENGTH_LONG).show()
        }
        updateDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildDashboardLayout())
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
    }

    override fun onDestroy() {
        testTts?.stop()
        testTts?.shutdown()
        testTts = null
        super.onDestroy()
    }

    private fun buildDashboardLayout(): ScrollView {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(context, R.color.tactical_black))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 64)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = "G.H.O.S.T. TERMINAL"
            textSize = 24f
            setTextColor(ContextCompat.getColor(context, R.color.tactical_cyan))
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "General Hardware & Operative System Tracker"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.tactical_text))
            setPadding(0, 0, 0, 32)
        }
        root.addView(subtitle)

        statusTextView = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.tactical_text))
            setPadding(0, 0, 0, 32)
        }
        root.addView(statusTextView)

        val btnOverlay = Button(this).apply {
            text = "1. Grant Overlay Authority"
            setOnClickListener { checkOrRequestOverlay() }
        }
        root.addView(btnOverlay)

        val btnPermissions = Button(this).apply {
            text = "2. Authorize Hardware (Mic, Camera, Phone)"
            setOnClickListener { checkAndRequestPermissions() }
        }
        root.addView(btnPermissions)

        val btnAccessibility = Button(this).apply {
            text = "3. Engage Accessibility Service"
            setOnClickListener { openAccessibilitySettings() }
        }
        root.addView(btnAccessibility)

        val btnDiagnostics = Button(this).apply {
            text = "Test Audio & Speech Diagnostics"
            setOnClickListener { runDiagnostics() }
        }
        root.addView(btnDiagnostics)

        toggleHudButton = Button(this).apply {
            text = "Deploy Tactical HUD"
            setBackgroundColor(ContextCompat.getColor(context, R.color.tactical_cyan_dim))
            setOnClickListener { toggleTacticalHud() }
        }
        root.addView(toggleHudButton)

        scrollView.addView(root)
        return scrollView
    }

    private fun updateDashboard() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = GhostAccessibilityService.isRunning
        val perms = getRequiredPermissions()
        val permsOk = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val speechOk = SpeechRecognizer.isRecognitionAvailable(this)

        val status = StringBuilder()
            .append("SYSTEM TELEMETRY:\n")
            .append("• Overlay Authority: ").append(if (overlayOk) "[ACTIVE]\n" else "[MISSING]\n")
            .append("• Accessibility Node Engine: ").append(if (accessibilityOk) "[LINKED]\n" else "[UNBOUND]\n")
            .append("• Hardware Permissions: ").append(if (permsOk) "[GRANTED]\n" else "[RESTRICTED]\n")
            .append("• Speech Recognizer Engine: ").append(if (speechOk) "[AVAILABLE]\n" else "[NOT DETECTED]\n")
            .toString()

        statusTextView.text = status
    }

    private fun checkOrRequestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Overlay authority confirmed.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun checkAndRequestPermissions() {
        val missing = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            Toast.makeText(this, "All permissions operational.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runDiagnostics() {
        val speechAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        Toast.makeText(this, "Running diagnostics...", Toast.LENGTH_SHORT).show()

        testTts?.stop()
        testTts?.shutdown()
        testTts = TextToSpeech(this) { status ->
            runOnUiThread {
                val ttsOk = (status == TextToSpeech.SUCCESS)
                if (ttsOk) {
                    testTts?.language = Locale.getDefault()
                    testTts?.speak("G.H.O.S.T. voice diagnostics operational.", TextToSpeech.QUEUE_FLUSH, null, "diag")
                }

                val diagReport = StringBuilder()
                    .append("DIAGNOSTIC REPORT:\n")
                    .append("• Mic Permission: ").append(if (micGranted) "[OK]\n" else "[DENIED - Click Button 2]\n")
                    .append("• Speech Recognizer: ").append(if (speechAvailable) "[READY]\n" else "[FAILED - Install Google app]\n")
                    .append("• Text-to-Speech Engine: ").append(if (ttsOk) "[OPERATIONAL]\n" else "[FAILED]\n")
                    .append("• Overlay Authority: ").append(if (Settings.canDrawOverlays(this)) "[GRANTED]\n" else "[MISSING]\n")
                    .append("• Accessibility Service: ").append(if (GhostAccessibilityService.isRunning) "[ACTIVE]\n" else "[DISABLED]\n")
                    .toString()

                statusTextView.text = diagReport
                Toast.makeText(this, "Diagnostics complete. TTS Status: ${if (ttsOk) "OK" else "ERROR"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleTacticalHud() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Error: System Alert Window permission required.", Toast.LENGTH_LONG).show()
            checkOrRequestOverlay()
            return
        }

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Toast.makeText(this, "Warning: Microphone permission missing. Authorize hardware first.", Toast.LENGTH_LONG).show()
            checkAndRequestPermissions()
            return
        }

        val serviceIntent = Intent(this, GhostOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "G.H.O.S.T. HUD deployed.", Toast.LENGTH_SHORT).show()
    }
}
