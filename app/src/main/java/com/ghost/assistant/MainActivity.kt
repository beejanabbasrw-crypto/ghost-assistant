package com.ghost.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var toggleHudButton: Button

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "All runtime capabilities authorized.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Hardware permissions denied. System capabilities degraded.", Toast.LENGTH_LONG).show()
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

    private fun buildDashboardLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            setBackgroundColor(ContextCompat.getColor(context, R.color.tactical_black))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "G.H.O.S.T. TERMINAL"
            textSize = 24f
            setTextColor(ContextCompat.getColor(context, R.color.tactical_cyan))
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 32)
        }
        root.addView(title)

        statusTextView = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.tactical_text))
            setPadding(0, 0, 0, 48)
        }
        root.addView(statusTextView)

        val btnOverlay = Button(this).apply {
            text = "Grant Overlay Window Permission"
            setOnClickListener { checkOrRequestOverlay() }
        }
        root.addView(btnOverlay)

        val btnAccessibility = Button(this).apply {
            text = "Engage Accessibility Service"
            setOnClickListener { openAccessibilitySettings() }
        }
        root.addView(btnAccessibility)

        val btnPermissions = Button(this).apply {
            text = "Authorize Hardware (Mic, Camera, Phone)"
            setOnClickListener { checkAndRequestPermissions() }
        }
        root.addView(btnPermissions)

        toggleHudButton = Button(this).apply {
            text = "Deploy Tactical HUD"
            setBackgroundColor(ContextCompat.getColor(context, R.color.tactical_cyan_dim))
            setOnClickListener { toggleTacticalHud() }
        }
        root.addView(toggleHudButton)

        return root
    }

    private fun updateDashboard() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = GhostAccessibilityService.isRunning
        val permsOk = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        val status = StringBuilder()
            .append("SYSTEM STATUS:\n")
            .append("• Overlay Authority: ").append(if (overlayOk) "[ACTIVE]\n" else "[MISSING]\n")
            .append("• Accessibility Node Engine: ").append(if (accessibilityOk) "[LINKED]\n" else "[UNBOUND]\n")
            .append("• Hardware Permissions: ").append(if (permsOk) "[GRANTED]\n" else "[RESTRICTED]\n")
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
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            Toast.makeText(this, "All permissions operational.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleTacticalHud() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Error: System Alert Window permission required.", Toast.LENGTH_LONG).show()
            checkOrRequestOverlay()
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
