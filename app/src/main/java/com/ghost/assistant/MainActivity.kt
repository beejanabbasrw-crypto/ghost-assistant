package com.ghost.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ghost.assistant.ai.ChatMessage
import com.ghost.assistant.ai.JarvisBrain
import com.ghost.assistant.audio.AudioCoreManager
import com.ghost.assistant.comms.CommsManager
import com.ghost.assistant.theme.ThemeManager
import com.ghost.assistant.ui.ArcReactorView
import com.ghost.assistant.ui.EqualizerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var mainContainer: FrameLayout
    private lateinit var navBarLayout: LinearLayout
    private lateinit var statusTextView: TextView

    private var currentNavIndex = 0

    // Modules
    private lateinit var jarvisBrain: JarvisBrain
    private lateinit var audioCoreManager: AudioCoreManager
    private var testTts: TextToSpeech? = null

    // Jarvis Chat UI State
    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatMessagesLayout: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatTypingIndicator: TextView

    // Audio Core UI State
    private var equalizerView: EqualizerView? = null
    private var audioNowPlayingText: TextView? = null

    // Net Link UI State
    private var netLinkWebView: WebView? = null
    private var netLinkProgressBar: ProgressBar? = null
    private var netLinkUrlInput: EditText? = null

    private val requiredPermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALENDAR
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return list.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val userName = ThemeManager.getUserName(this)
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Hardware capabilities authorized, $userName.", Toast.LENGTH_SHORT).show()
            if (ThemeManager.isWakeWordEnabled(this)) {
                com.ghost.assistant.wakeword.GhostWakeWordService.startService(this)
            }
        } else {
            Toast.makeText(this, "Some permissions were restricted, $userName.", Toast.LENGTH_LONG).show()
        }
        updateDashboardTelemetry()
    }

    private val audioFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val name = uri.lastPathSegment ?: "Audio Track"
            audioCoreManager.playLocalFile(uri, name)
            audioNowPlayingText?.text = "PLAYING: $name"
            equalizerView?.setPlaying(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(ThemeManager.getThemeMode(this))
        super.onCreate(savedInstanceState)

        jarvisBrain = JarvisBrain(this)
        audioCoreManager = AudioCoreManager(this)
        CommsManager.createNotificationChannel(this)

        if (ThemeManager.isWakeWordEnabled(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            com.ghost.assistant.wakeword.GhostWakeWordService.startService(this)
        }

        setContentView(buildRootLayout())
        showScreen(0)
    }

    override fun onResume() {
        super.onResume()
        updateDashboardTelemetry()
    }

    override fun onDestroy() {
        audioCoreManager.release()
        testTts?.stop()
        testTts?.shutdown()
        testTts = null
        netLinkWebView?.destroy()
        super.onDestroy()
    }

    private fun buildRootLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ThemeManager.getBackgroundColor(this@MainActivity))
        }

        // 1. Top Stark HUD Header
        val headerView = buildHeaderView()
        root.addView(headerView)

        // 2. Dynamic Content Screen Container
        mainContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(mainContainer)

        // 3. Bottom HUD Navigation Bar
        navBarLayout = buildBottomNavBar()
        root.addView(navBarLayout)

        return root
    }

    private fun buildHeaderView(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 20)
            setBackgroundColor(ThemeManager.getPanelColor(this@MainActivity))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "G.H.O.S.T. // STARK HUD"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(title)

        val statusDot = TextView(this).apply {
            text = "● ONLINE"
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.status_green))
            paint.isFakeBoldText = true
        }
        topRow.addView(statusDot)
        header.addView(topRow)

        val userName = ThemeManager.getUserName(this)
        val badge = TextView(this).apply {
            text = "OPERATIVE: ${userName.uppercase(Locale.US)} — CLEARANCE LVL 10"
            textSize = 11f
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(0, 4, 0, 0)
        }
        header.addView(badge)

        val separator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                topMargin = 16
            }
            setBackgroundColor(ThemeManager.getBorderColor(this@MainActivity))
        }
        header.addView(separator)

        return header
    }

    private fun buildBottomNavBar(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ThemeManager.getPanelColor(this@MainActivity))
            setPadding(8, 12, 8, 16)
            weightSum = 5f
        }

        val tabs = listOf(
            "⚡\nHUD",
            "🤖\nJARVIS",
            "🎵\nAUDIO",
            "🌐\nNET",
            "⚙️\nCONFIG"
        )

        for (i in tabs.indices) {
            val btn = Button(this).apply {
                text = tabs[i]
                textSize = 10f
                isAllCaps = true
                setTextColor(if (i == 0) ContextCompat.getColor(context, R.color.arc_cyan) else ThemeManager.getSecondaryTextColor(this@MainActivity))
                setBackgroundResource(R.drawable.holo_button_bg)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
                setOnClickListener {
                    showScreen(i)
                }
            }
            nav.addView(btn)
        }
        return nav
    }

    private fun updateNavSelection(index: Int) {
        currentNavIndex = index
        for (i in 0 until navBarLayout.childCount) {
            val btn = navBarLayout.getChildAt(i) as? Button ?: continue
            if (i == index) {
                btn.setTextColor(ContextCompat.getColor(this, R.color.arc_cyan))
                btn.paint.isFakeBoldText = true
            } else {
                btn.setTextColor(ThemeManager.getSecondaryTextColor(this))
                btn.paint.isFakeBoldText = false
            }
        }
    }

    private fun showScreen(index: Int) {
        updateNavSelection(index)
        mainContainer.removeAllViews()

        val screenView: View = when (index) {
            0 -> buildDashboardScreen()
            1 -> buildJarvisScreen()
            2 -> buildAudioCoreScreen()
            3 -> buildNetLinkScreen()
            4 -> buildConfigScreen()
            else -> buildDashboardScreen()
        }

        screenView.alpha = 0f
        mainContainer.addView(screenView)
        screenView.animate().alpha(1f).setDuration(220).start()
    }

    // ==========================================
    // 1. DASHBOARD SCREEN (COMMAND & TELEMETRY)
    // ==========================================
    private fun buildDashboardScreen(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 48)
        }

        // Arc Reactor Widget
        val reactorCard = createHoloCard()
        val reactorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 32, 24, 32)
        }

        val arcReactorView = ArcReactorView(this).apply {
            layoutParams = LinearLayout.LayoutParams(220, 220).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                toggleTacticalHud()
            }
        }
        reactorLayout.addView(arcReactorView)

        val reactorStatus = TextView(this).apply {
            text = "ARC REACTOR // 100% NOMINAL OUTPUT"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
            setPadding(0, 16, 0, 4)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        reactorLayout.addView(reactorStatus)

        val reactorSub = TextView(this).apply {
            val userName = ThemeManager.getUserName(this@MainActivity)
            text = "Autonomous Core active for $userName\nTap Arc Reactor to engage HUD • Wake Word: 'GHOST'"
            textSize = 11f
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        reactorLayout.addView(reactorSub)
        reactorCard.addView(reactorLayout)
        content.addView(reactorCard)

        // Telemetry readout card
        val telemetryCard = createHoloCard().apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 24
        }
        val telemetryLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val telemetryTitle = TextView(this).apply {
            text = "SYSTEM TELEMETRY ARCHIVE"
            textSize = 13f
            paint.isFakeBoldText = true
            setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
            setPadding(0, 0, 0, 12)
        }
        telemetryLayout.addView(telemetryTitle)

        statusTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            setLineSpacing(6f, 1f)
        }
        telemetryLayout.addView(statusTextView)
        telemetryCard.addView(telemetryLayout)
        content.addView(telemetryCard)

        // Action Buttons Grid
        val actionsTitle = TextView(this).apply {
            text = "TACTICAL HARDWARE INTERACTION"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 32, 0, 12)
        }
        content.addView(actionsTitle)

        val btnOverlay = createHoloButton("DEPLOY TACTICAL HUD OVERLAY") {
            toggleTacticalHud()
        }
        content.addView(btnOverlay)

        val btnPermissions = createHoloButton("AUTHORIZE HARDWARE (MIC, CAM, CALL)") {
            checkAndRequestPermissions()
        }
        content.addView(btnPermissions)

        val btnAccessibility = createHoloButton("ENGAGE ACCESSIBILITY NODE ENGINE") {
            openAccessibilitySettings()
        }
        content.addView(btnAccessibility)

        val btnDiagnostics = createHoloButton("RUN AUDIO & VOICE DIAGNOSTICS") {
            runDiagnostics()
        }
        content.addView(btnDiagnostics)

        val btnWakeWordService = createHoloButton("START BACKGROUND WAKE WORD SERVICE ('GHOST')") {
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                com.ghost.assistant.wakeword.GhostWakeWordService.startService(this)
                Toast.makeText(this, "Background Wake Word Service engaged ('GHOST').", Toast.LENGTH_SHORT).show()
                updateDashboardTelemetry()
            } else {
                checkAndRequestPermissions()
            }
        }
        content.addView(btnWakeWordService)

        val btnCommsAlert = createHoloButton("DISPATCH TEST COMMS ALERT") {
            val userName = ThemeManager.getUserName(this)
            val sent = CommsManager.sendCommsAlert(
                this,
                "J.A.R.V.I.S. COMMS LINK",
                "Tactical alert verified, $userName. Diagnostic pulse normal."
            )
            if (sent) {
                Toast.makeText(this, "Comms alert dispatched to status bar, $userName.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Comms alerts disabled or notification permission required.", Toast.LENGTH_LONG).show()
            }
        }
        content.addView(btnCommsAlert)

        scroll.addView(content)
        updateDashboardTelemetry()
        return scroll
    }

    private fun updateDashboardTelemetry() {
        if (!::statusTextView.isInitialized) return
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = GhostAccessibilityService.isRunning
        val permsOk = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val speechOk = SpeechRecognizer.isRecognitionAvailable(this)
        val userName = ThemeManager.getUserName(this)

        val status = StringBuilder()
            .append("• Assigned Operative: ").append(userName).append("\n")
            .append("• Holographic Overlay: ").append(if (overlayOk) "[ACTIVE]" else "[STANDBY]").append("\n")
            .append("• Accessibility Node: ").append(if (accessibilityOk) "[LINKED]" else "[UNBOUND]").append("\n")
            .append("• Hardware Permissions: ").append(if (permsOk) "[AUTHORIZED]" else "[RESTRICTED]").append("\n")
            .append("• Voice Recognition: ").append(if (speechOk) "[OPERATIONAL]" else "[NOT DETECTED]").append("\n")
            .append("• Wake Word Service: ").append(if (ThemeManager.isWakeWordEnabled(this)) "[GHOST LISTENING]" else "[STANDBY]").append("\n")
            .append("• Notification Node: ").append(if (com.ghost.assistant.notification.GhostNotificationListenerService.isServiceConnected) "[LINKED]" else "[STANDBY]").append("\n")
            .append("• Laptop Companion: ").append("[${ThemeManager.getCompanionHost(this)}:${ThemeManager.getCompanionPort(this)}]").append("\n")
            .append("• Stark Comms Channel: ").append(if (ThemeManager.isCommsEnabled(this)) "[ACTIVE]" else "[MUTED]")
            .toString()

        statusTextView.text = status
    }

    // ==========================================
    // 2. JARVIS AI CHAT SCREEN
    // ==========================================
    private fun buildJarvisScreen(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(32, 16, 32, 16)
        }

        val userName = ThemeManager.getUserName(this)

        // Quick Suggestion Chips
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val promptChips = listOf(
            "System Status",
            "Arc Reactor Report",
            "What can you do?",
            "House Party Protocol",
            "Current Time",
            "Clear Telemetry"
        )

        for (prompt in promptChips) {
            val chip = Button(this).apply {
                text = prompt
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
                setBackgroundResource(R.drawable.holo_button_bg)
                setPadding(24, 8, 24, 8)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 0, 8, 0)
                }
                setOnClickListener {
                    if (prompt == "Clear Telemetry") {
                        chatMessages.clear()
                        chatMessagesLayout.removeAllViews()
                        addChatMessage(jarvisBrain.getInitialGreeting())
                    } else {
                        handleSendUserMessage(prompt)
                    }
                }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // Chat Message List
        chatScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        chatMessagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        chatScrollView.addView(chatMessagesLayout)
        root.addView(chatScrollView)

        // Typing indicator
        chatTypingIndicator = TextView(this).apply {
            text = "J.A.R.V.I.S. is calculating..."
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
            visibility = View.GONE
            setPadding(16, 8, 16, 8)
        }
        root.addView(chatTypingIndicator)

        // Initial Greeting if empty
        if (chatMessages.isEmpty()) {
            addChatMessage(jarvisBrain.getInitialGreeting())
        } else {
            for (msg in chatMessages) {
                renderChatMessageView(msg)
            }
        }

        // Chat Input Row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 8)
        }

        val inputEdit = EditText(this).apply {
            hint = "Transmit directive to J.A.R.V.I.S...."
            setHintTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            textSize = 13f
            setBackgroundResource(R.drawable.holo_input_bg)
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 12
            }
        }
        inputRow.addView(inputEdit)

        val sendBtn = Button(this).apply {
            text = "TRANSMIT"
            textSize = 11f
            paint.isFakeBoldText = true
            setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
            setBackgroundResource(R.drawable.holo_button_bg)
            setPadding(28, 20, 28, 20)
            setOnClickListener {
                val text = inputEdit.text.toString().trim()
                if (text.isNotBlank()) {
                    inputEdit.setText("")
                    handleSendUserMessage(text)
                }
            }
        }
        inputRow.addView(sendBtn)
        root.addView(inputRow)

        return root
    }

    private fun handleSendUserMessage(text: String) {
        val userName = ThemeManager.getUserName(this)
        val userMsg = ChatMessage(sender = userName, message = text, isJarvis = false)
        addChatMessage(userMsg)

        chatTypingIndicator.visibility = View.VISIBLE
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }

        scope.launch {
            delay(400) // Brief natural calculation latency
            val reply = jarvisBrain.getResponse(text)
            chatTypingIndicator.visibility = View.GONE
            val jarvisMsg = ChatMessage(sender = "J.A.R.V.I.S.", message = reply, isJarvis = true)
            addChatMessage(jarvisMsg)
        }
    }

    private fun addChatMessage(msg: ChatMessage) {
        chatMessages.add(msg)
        renderChatMessageView(msg)
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderChatMessageView(msg: ChatMessage) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundResource(if (msg.isJarvis) R.drawable.jarvis_bubble_bg else R.drawable.user_bubble_bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (msg.isJarvis) Gravity.START else Gravity.END
                setMargins(
                    if (msg.isJarvis) 0 else 80,
                    8,
                    if (msg.isJarvis) 80 else 0,
                    8
                )
            }

            setOnLongClickListener {
                copyToClipboard(msg.message)
                Toast.makeText(context, "Message copied to clipboard.", Toast.LENGTH_SHORT).show()
                true
            }
        }

        val sender = TextView(this).apply {
            text = msg.sender
            textSize = 10f
            paint.isFakeBoldText = true
            setTextColor(if (msg.isJarvis) ContextCompat.getColor(context, R.color.arc_cyan) else ContextCompat.getColor(context, R.color.repulsor_blue))
            setPadding(0, 0, 0, 4)
        }
        bubble.addView(sender)

        val text = TextView(this).apply {
            this.text = msg.message
            textSize = 13f
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            setLineSpacing(4f, 1f)
        }
        bubble.addView(text)

        chatMessagesLayout.addView(bubble)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Jarvis Message", text)
        clipboard.setPrimaryClip(clip)
    }

    // ==========================================
    // 3. AUDIO CORE SCREEN
    // ==========================================
    private fun buildAudioCoreScreen(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 48)
        }

        val equalizerCard = createHoloCard()
        val eqLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 32, 24, 32)
        }

        val eqView = EqualizerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160).apply {
                setMargins(16, 16, 16, 16)
            }
        }
        equalizerView = eqView
        eqLayout.addView(eqView)

        val nowPlaying = TextView(this).apply {
            text = "AUDIO CORE // STANDBY"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        audioNowPlayingText = nowPlaying
        eqLayout.addView(nowPlaying)
        equalizerCard.addView(eqLayout)
        content.addView(equalizerCard)

        // Source Selection
        val sourceTitle = TextView(this).apply {
            text = "AUDIO HARMONIC SOURCE"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(sourceTitle)

        val btnAmbient = createHoloButton("SYNTHESIZE TACTICAL AMBIENT CORE (108 HZ)") {
            audioCoreManager.playAmbientCore()
            nowPlaying.text = "PLAYING: Tactical Ambient Core (108 Hz)"
            equalizerView?.setPlaying(true)
            Toast.makeText(this, "Harmonic resonance engaged.", Toast.LENGTH_SHORT).show()
        }
        content.addView(btnAmbient)

        val btnLoadLocal = createHoloButton("LOAD LOCAL AUDIO FILE (SAF PICKER)") {
            audioFilePickerLauncher.launch(arrayOf("audio/*"))
        }
        content.addView(btnLoadLocal)

        // Playback Controls Row
        val controlsTitle = TextView(this).apply {
            text = "PLAYBACK MODULATION"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 24, 0, 12)
        }
        content.addView(controlsTitle)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }

        val btnResume = createHoloButton("PLAY") {
            audioCoreManager.resume()
            equalizerView?.setPlaying(true)
            nowPlaying.text = "PLAYING: ${audioCoreManager.nowPlayingTitle}"
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
        }
        controlsRow.addView(btnResume)

        val btnPause = createHoloButton("PAUSE") {
            audioCoreManager.pause()
            equalizerView?.setPlaying(false)
            nowPlaying.text = "PAUSED: ${audioCoreManager.nowPlayingTitle}"
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
        }
        controlsRow.addView(btnPause)

        val btnStop = createHoloButton("STOP") {
            audioCoreManager.stop()
            equalizerView?.setPlaying(false)
            nowPlaying.text = "AUDIO CORE // STANDBY"
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
        }
        controlsRow.addView(btnStop)
        content.addView(controlsRow)

        scroll.addView(content)
        return scroll
    }

    // ==========================================
    // 4. NET LINK SCREEN (HUD WEB BROWSER)
    // ==========================================
    private fun buildNetLinkScreen(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(24, 16, 24, 16)
        }

        // Top Navigation Bar
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }

        val urlEdit = EditText(this).apply {
            hint = "Enter URL (e.g. google.com)..."
            setHintTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            textSize = 12f
            setBackgroundResource(R.drawable.holo_input_bg)
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 8
            }
        }
        netLinkUrlInput = urlEdit
        navRow.addView(urlEdit)

        val goBtn = Button(this).apply {
            text = "SCAN"
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
            setBackgroundResource(R.drawable.holo_button_bg)
            setPadding(20, 14, 20, 14)
            setOnClickListener {
                var url = urlEdit.text.toString().trim()
                if (url.isNotBlank()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    netLinkWebView?.loadUrl(url)
                }
            }
        }
        navRow.addView(goBtn)
        root.addView(navRow)

        // Progress Bar
        val pBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                6
            ).apply {
                bottomMargin = 8
            }
            max = 100
            progress = 0
            visibility = View.GONE
        }
        netLinkProgressBar = pBar
        root.addView(pBar)

        // HUD Framed WebView Container
        val frameCard = createHoloCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    netLinkUrlInput?.setText(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        netLinkProgressBar?.visibility = View.VISIBLE
                        netLinkProgressBar?.progress = newProgress
                    } else {
                        netLinkProgressBar?.visibility = View.GONE
                    }
                }
            }
        }
        netLinkWebView = webView
        frameCard.addView(webView)
        root.addView(frameCard)

        // Load Default Safe Portal
        webView.loadUrl("https://en.wikipedia.org/wiki/Main_Page")
        return root
    }

    // ==========================================
    // 5. SYSTEM CONFIG SCREEN (SETTINGS)
    // ==========================================
    private fun buildConfigScreen(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 48)
        }

        val currentName = ThemeManager.getUserName(this)

        // 1. Callsign Config
        val callsignTitle = TextView(this).apply {
            text = "OPERATIVE CALLSIGN CONFIGURATION"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 8, 0, 12)
        }
        content.addView(callsignTitle)

        val callsignCard = createHoloCard()
        val callsignLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }

        val callsignEdit = EditText(this).apply {
            setText(currentName)
            textSize = 14f
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            setBackgroundResource(R.drawable.holo_input_bg)
            setPadding(24, 16, 24, 16)
        }
        callsignLayout.addView(callsignEdit)

        val saveCallsignBtn = createHoloButton("UPDATE CALLSIGN") {
            val entered = callsignEdit.text.toString().trim()
            if (entered.isNotBlank()) {
                ThemeManager.setUserName(this, entered)
                Toast.makeText(this, "Callsign updated to $entered.", Toast.LENGTH_SHORT).show()
                updateDashboardTelemetry()
            }
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 16
        }
        callsignLayout.addView(saveCallsignBtn)
        callsignCard.addView(callsignLayout)
        content.addView(callsignCard)

        // 2. Theme Selection (Stealth vs Mark vs System)
        val themeTitle = TextView(this).apply {
            text = "HUD VISUAL INTERFACE THEME"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(themeTitle)

        val themeCard = createHoloCard()
        val themeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }

        val radioGroup = RadioGroup(this)
        val currentMode = ThemeManager.getThemeMode(this)

        val rbStealth = RadioButton(this).apply {
            text = "Stealth Mode (Dark Stark Workshop HUD)"
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            isChecked = (currentMode == ThemeManager.ThemeMode.STEALTH)
        }
        radioGroup.addView(rbStealth)

        val rbMark = RadioButton(this).apply {
            text = "Mark Mode (Light Titanium Daylight HUD)"
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            isChecked = (currentMode == ThemeManager.ThemeMode.MARK)
        }
        radioGroup.addView(rbMark)

        val rbSystem = RadioButton(this).apply {
            text = "System Default Synchronized"
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            isChecked = (currentMode == ThemeManager.ThemeMode.SYSTEM)
        }
        radioGroup.addView(rbSystem)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                rbStealth.id -> ThemeManager.ThemeMode.STEALTH
                rbMark.id -> ThemeManager.ThemeMode.MARK
                else -> ThemeManager.ThemeMode.SYSTEM
            }
            ThemeManager.setThemeMode(this, newMode)
            Toast.makeText(this, "Applying ${newMode.name} interface...", Toast.LENGTH_SHORT).show()
            recreate()
        }
        themeLayout.addView(radioGroup)
        themeCard.addView(themeLayout)
        content.addView(themeCard)

        // 3. Comms Notifications Toggle
        val commsTitle = TextView(this).apply {
            text = "COMMS & TACTICAL DISPATCH"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(commsTitle)

        val commsCard = createHoloCard()
        val commsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }

        val commsCheck = CheckBox(this).apply {
            text = "Enable Stark Comms & Intelligence Alerts"
            isChecked = ThemeManager.isCommsEnabled(this@MainActivity)
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            setOnCheckedChangeListener { _, isChecked ->
                ThemeManager.setCommsEnabled(this@MainActivity, isChecked)
                Toast.makeText(context, "Comms notifications ${if (isChecked) "enabled" else "muted"}.", Toast.LENGTH_SHORT).show()
                updateDashboardTelemetry()
            }
        }
        commsLayout.addView(commsCheck)
        commsCard.addView(commsLayout)
        content.addView(commsCard)

        // 4. Wake Word Detection (Hotword: GHOST)
        val wakeWordTitle = TextView(this).apply {
            text = "VOICE WAKE WORD ENGINE (HOTWORD: GHOST)"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(wakeWordTitle)

        val wakeWordCard = createHoloCard()
        val wakeWordLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }

        val wakeWordCheck = CheckBox(this).apply {
            text = "Enable Wake Word Recognition ('GHOST' / 'Hey Ghost')"
            isChecked = ThemeManager.isWakeWordEnabled(this@MainActivity)
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            setOnCheckedChangeListener { _, isChecked ->
                ThemeManager.setWakeWordEnabled(this@MainActivity, isChecked)
                if (isChecked) {
                    val micGranted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (micGranted) {
                        com.ghost.assistant.wakeword.GhostWakeWordService.startService(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Wake word engine ('GHOST') active in background.", Toast.LENGTH_SHORT).show()
                    } else {
                        checkAndRequestPermissions()
                    }
                } else {
                    com.ghost.assistant.wakeword.GhostWakeWordService.stopService(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Wake word engine standby.", Toast.LENGTH_SHORT).show()
                }
                updateDashboardTelemetry()
            }
        }
        wakeWordLayout.addView(wakeWordCheck)

        val wakeWordDesc = TextView(this).apply {
            text = "Autonomous Background Voice Activation:\nRuns a persistent foreground service with continuous microphone listening for 'Ghost' or 'Hey Ghost'. Pauses during speech to avoid self-triggers, auto-recovers on error, and activates on spoken directives (e.g. 'Ghost, open Chrome' or 'Ghost, play Bohemian Rhapsody')."
            textSize = 11f
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 12, 8, 4)
            setLineSpacing(4f, 1f)
        }
        wakeWordLayout.addView(wakeWordDesc)
        wakeWordCard.addView(wakeWordLayout)
        content.addView(wakeWordCard)

        // 5. Notification Read & Reply Authorization
        val notifTitle = TextView(this).apply {
            text = "NOTIFICATION INTELLIGENCE NODE"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(notifTitle)

        val notifCard = createHoloCard()
        val notifLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }

        val btnNotifAuth = createHoloButton("AUTHORIZE NOTIFICATION READ/REPLY ACCESS") {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
        notifLayout.addView(btnNotifAuth)

        val notifDesc = TextView(this).apply {
            text = "Enables voice directives like 'Ghost, read notifications' to hear incoming messages and 'Ghost, reply [message]' to respond directly via speech."
            textSize = 11f
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 8, 8, 4)
        }
        notifLayout.addView(notifDesc)
        notifCard.addView(notifLayout)
        content.addView(notifCard)

        // 6. Laptop Companion Mode Configuration
        val companionTitle = TextView(this).apply {
            text = "LAPTOP COMPANION PROTOCOL (DESKTOP MODE)"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(companionTitle)

        val companionCard = createHoloCard()
        val companionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }

        val companionHostEdit = EditText(this).apply {
            hint = "Desktop Host IP (e.g. 192.168.1.100)..."
            setHintTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setText(ThemeManager.getCompanionHost(this@MainActivity))
            textSize = 12f
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            setBackgroundResource(R.drawable.holo_input_bg)
            setPadding(24, 16, 24, 16)
        }
        companionLayout.addView(companionHostEdit)

        val companionPortEdit = EditText(this).apply {
            hint = "Desktop Companion Port (e.g. 8080)..."
            setHintTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setText(ThemeManager.getCompanionPort(this@MainActivity).toString())
            textSize = 12f
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            setBackgroundResource(R.drawable.holo_input_bg)
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
        }
        companionLayout.addView(companionPortEdit)

        val saveCompanionBtn = createHoloButton("SAVE COMPANION CONFIG") {
            val host = companionHostEdit.text.toString().trim()
            val port = companionPortEdit.text.toString().trim().toIntOrNull() ?: 8080
            ThemeManager.setCompanionHost(this, host)
            ThemeManager.setCompanionPort(this, port)
            Toast.makeText(this, "Companion endpoint updated to $host:$port.", Toast.LENGTH_SHORT).show()
            updateDashboardTelemetry()
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 16
        }
        companionLayout.addView(saveCompanionBtn)

        val companionDesc = TextView(this).apply {
            text = "Voice controls for your paired computer: 'Ghost, laptop open Chrome', 'laptop volume up', 'laptop search files for project', 'laptop lock', or 'laptop status'."
            textSize = 11f
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 8, 8, 4)
        }
        companionLayout.addView(companionDesc)
        companionCard.addView(companionLayout)
        content.addView(companionCard)

        // 7. Custom AI Endpoint
        val aiTitle = TextView(this).apply {
            text = "JARVIS AI ENDPOINT PROTOCOL"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(aiTitle)

        val aiCard = createHoloCard()
        val aiLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }

        val aiEndpointEdit = EditText(this).apply {
            hint = "Custom API URL (Optional, defaults to Local Jarvis)..."
            setHintTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setText(ThemeManager.getAiCustomEndpoint(this@MainActivity))
            textSize = 12f
            setTextColor(ThemeManager.getPrimaryTextColor(this@MainActivity))
            setBackgroundResource(R.drawable.holo_input_bg)
            setPadding(24, 16, 24, 16)
        }
        aiLayout.addView(aiEndpointEdit)

        val saveAiBtn = createHoloButton("SAVE AI ENDPOINT") {
            val url = aiEndpointEdit.text.toString().trim()
            ThemeManager.setAiCustomEndpoint(this, url)
            Toast.makeText(this, "AI configuration saved.", Toast.LENGTH_SHORT).show()
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 16
        }
        aiLayout.addView(saveAiBtn)
        aiCard.addView(aiLayout)
        content.addView(aiCard)

        // 8. Identity Verification Modal
        val secTitle = TextView(this).apply {
            text = "IDENTITY VERIFICATION & CLEARANCE"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(secTitle)

        val btnVerify = createHoloButton("AUTHENTICATE BIOMETRIC CLEARANCE") {
            val user = ThemeManager.getUserName(this)
            Toast.makeText(this, "Scanning biometrics... Clearance verified for $user.", Toast.LENGTH_LONG).show()
        }
        content.addView(btnVerify)

        // 6. About Section
        val aboutTitle = TextView(this).apply {
            text = "ABOUT G.H.O.S.T. & PRIVACY"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setPadding(8, 28, 0, 12)
        }
        content.addView(aboutTitle)

        val aboutCard = createHoloCard()
        val aboutLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }

        val aboutText = TextView(this).apply {
            text = "G.H.O.S.T. (General Hardware & Operative System Tracker)\nVersion: 1.0.0 Tactical Edition\nArchitecture: Stark Industries Holographic Interface & J.A.R.V.I.S. Core\n\nPrivacy Guarantee:\nAll voice processing and device automation execute locally on host. No telemetry or credentials are transmitted to third-party tracking networks."
            textSize = 11f
            setTextColor(ThemeManager.getSecondaryTextColor(this@MainActivity))
            setLineSpacing(4f, 1f)
        }
        aboutLayout.addView(aboutText)
        aboutCard.addView(aboutLayout)
        content.addView(aboutCard)

        scroll.addView(content)
        return scroll
    }

    // ==========================================
    // UI BUILDER HELPERS
    // ==========================================
    private fun createHoloCard(): FrameLayout {
        return FrameLayout(this).apply {
            setBackgroundResource(R.drawable.holo_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }
    }

    private fun createHoloButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(ContextCompat.getColor(context, R.color.arc_cyan))
            setBackgroundResource(R.drawable.holo_button_bg)
            setPadding(24, 28, 24, 28)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            setOnClickListener { onClick() }
        }
    }

    // ==========================================
    // HARDWARE & OVERLAY INTERACTION
    // ==========================================
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
            val userName = ThemeManager.getUserName(this)
            Toast.makeText(this, "All runtime capabilities operational, $userName.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runDiagnostics() {
        val speechAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val userName = ThemeManager.getUserName(this)

        Toast.makeText(this, "Running diagnostics for $userName...", Toast.LENGTH_SHORT).show()

        testTts?.stop()
        testTts?.shutdown()
        testTts = TextToSpeech(this) { status ->
            runOnUiThread {
                val ttsOk = (status == TextToSpeech.SUCCESS)
                if (ttsOk) {
                    testTts?.language = Locale.getDefault()
                    testTts?.speak("All systems operational for $userName. J.A.R.V.I.S. online.", TextToSpeech.QUEUE_FLUSH, null, "diag")
                }

                val diagReport = StringBuilder()
                    .append("• Assigned Operative: ").append(userName).append("\n")
                    .append("• Mic Permission: ").append(if (micGranted) "[OK]" else "[DENIED]").append("\n")
                    .append("• Speech Recognizer: ").append(if (speechAvailable) "[READY]" else "[NOT FOUND]").append("\n")
                    .append("• Text-to-Speech: ").append(if (ttsOk) "[OPERATIONAL]" else "[FAILED]").append("\n")
                    .append("• Overlay Authority: ").append(if (Settings.canDrawOverlays(this)) "[GRANTED]" else "[MISSING]").append("\n")
                    .append("• Accessibility Node: ").append(if (GhostAccessibilityService.isRunning) "[LINKED]" else "[UNBOUND]")
                    .toString()

                statusTextView.text = diagReport
                Toast.makeText(this, "Diagnostics complete. TTS Status: ${if (ttsOk) "OK" else "ERROR"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleTacticalHud() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "System Alert Window overlay permission required.", Toast.LENGTH_LONG).show()
            checkOrRequestOverlay()
            return
        }

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            val userName = ThemeManager.getUserName(this)
            Toast.makeText(this, "Microphone permission required, $userName.", Toast.LENGTH_LONG).show()
            checkAndRequestPermissions()
            return
        }

        val serviceIntent = Intent(this, GhostOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        val userName = ThemeManager.getUserName(this)
        Toast.makeText(this, "G.H.O.S.T. HUD deployed on screen for $userName.", Toast.LENGTH_SHORT).show()
    }
}
