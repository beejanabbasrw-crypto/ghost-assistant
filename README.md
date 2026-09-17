# G.H.O.S.T. (General Hardware & Operative System Tracker)
**Tactical Mobile Terminal Voice Assistant for Android**

G.H.O.S.T. is an advanced, privacy-first voice assistant Android app inspired by Tony Stark's J.A.R.V.I.S. HUD architecture. It combines persistent background wake word listening, structured intent routing, device hardware toggles, multimedia control, contact messaging (SMS and WhatsApp), alarm and timer automation, live weather updates, notification read/reply, screen captures, and a desktop companion mode.

---

## Key Capabilities & Updates

### 1. Autonomous Background Wake Word Detection ("Ghost")
- **Persistent Foreground Service**: Runs `GhostWakeWordService` with low power consumption and continuous microphone listening.
- **Microphone Permission Handling**: Validates `RECORD_AUDIO` and `FOREGROUND_SERVICE_MICROPHONE` (Android 14+ / API 34).
- **Auto-Restart & Watchdog Recovery**: Built-in 10-second watchdog and automatic recovery on client/audio errors (`ERROR_CLIENT`, `ERROR_RECOGNIZER_BUSY`, `ERROR_SPEECH_TIMEOUT`, `ERROR_NO_MATCH`).
- **TTS Speech Coordination**: Automatically pauses listening during assistant speech output to prevent self-triggering feedback loops, and resumes immediately upon completion.
- **Boot Recovery**: `BootReceiver` restarts wake word service on device reboot if enabled.

### 2. Device Toggles (Wifi, Bluetooth, Hotspot, Mobile Data)
Respects Android's platform security policies while providing clear spoken feedback explaining whether an action was executed directly or by opening a settings screen:
- **Bluetooth**: Direct programmatic toggle using `BluetoothAdapter` with `BLUETOOTH_CONNECT` permission (Android 12+). Falls back to Bluetooth settings if restricted by the OS.
- **Wifi**: On Android 10+ (API 29+), apps cannot silently toggle Wi-Fi; opens the system panel via `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` intent.
- **Hotspot**: No public API exists for third-party apps to toggle tethering; opens `Settings.ACTION_WIRELESS_SETTINGS` / tethering screen.
- **Mobile Data**: Direct toggle is restricted without system-level/root privileges; opens mobile network settings.

### 3. Comprehensive Intent Router
Eliminates the "no action found" issue by decoupling recognition from brittle string matching:
- **Input Normalization**: Removes wake words ("Ghost", "Hey Ghost", "Jarvis"), conversational fillers ("please", "can you", "could you"), and punctuation.
- **Structured Intent Matching**: Strongly typed `GhostIntent` pipeline covering device controls, music, messaging, phone calls, illumination, volume, brightness, alarms, timers, calendar, weather, notifications, and desktop companion directives.
- **Helpful Voice Fallback**: Unrecognized speech produces clear spoken guidance: `"I didn't catch a command for that, Abdur. Say 'help' to hear available commands."`

### 4. Music & Media Playback
- **"play [song/artist]"**:
  - Broadcasts `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` with `SearchManager.QUERY` for the user's default music player.
  - Automatically targets Spotify (`com.spotify.music`) or YouTube Music (`com.google.android.apps.youtube.music`) with direct search intents.
  - Fallback to YouTube app and web browser.
- **Playback Controls**: Direct media key dispatch (`play`, `pause`, `stop`, `next track`, `previous track`).

### 5. Contact Messaging (Direct SMS & WhatsApp)
- **"message [contact name] [text]"** / **"whatsapp [contact] [text]"**:
  - Resolves contact names and phone numbers via `ContactsContract.CommonDataKinds.Phone` using exact, prefix, and contains matching.
  - Direct transmission via `SmsManager` when `SEND_SMS` permission is granted.
  - Automatic or requested handoff to WhatsApp direct chat via intent (`https://api.whatsapp.com/send?phone=...&text=...`).

### 6. Additional Integrated Features
- **Alarms, Timers & Reminders**: "set alarm for 7:30 AM", "set timer for 10 minutes", "remind me to call Mom".
- **Phone Calls**: "call [contact name/number]" via `Intent.ACTION_CALL` or dialer fallback.
- **Notification Read & Voice Reply**: `GhostNotificationListenerService` reads aloud incoming notifications and replies to messages using `RemoteInput`.
- **Flashlight**: Toggle tactical illuminator via `CameraManager`.
- **Volume & Brightness Control**: Adjust media volume by percentage or step; modulate screen brightness with `WRITE_SETTINGS` verification.
- **Open Apps by Name**: Intelligent package name and launcher activity resolution.
- **Calendar Readout**: Reads today's agenda via `CalendarContract.Instances`.
- **Live Weather Lookup**: Queries current temperature, conditions, and wind speed via `wttr.in` without requiring an API key.
- **Screenshot & Lock Screen**: Powered by `GhostAccessibilityService`.
- **Laptop Companion Mode**: Transmits system commands ("laptop open [app]", "laptop volume up", "laptop search files [query]", "laptop lock") to `companion/ghost_desktop_companion.py` over local Wi-Fi.

---

## Project Structure

```
ghost-assistant/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/ghost/assistant/
│   │       ├── MainActivity.kt
│   │       ├── GhostBrain.kt
│   │       ├── GhostOverlayService.kt
│   │       ├── GhostAccessibilityService.kt
│   │       ├── SystemBridge.kt
│   │       ├── device/DeviceToggleManager.kt
│   │       ├── media/MusicManager.kt
│   │       ├── messaging/ContactMessagingManager.kt
│   │       ├── features/
│   │       │   ├── AlarmClockManager.kt
│   │       │   ├── CalendarManager.kt
│   │       │   ├── WeatherManager.kt
│   │       │   └── CompanionManager.kt
│   │       ├── notification/GhostNotificationListenerService.kt
│   │       ├── router/IntentRouter.kt
│   │       ├── wakeword/
│   │       │   ├── WakeWordManager.kt
│   │       │   └── GhostWakeWordService.kt
│   │       ├── receiver/BootReceiver.kt
│   │       ├── ai/JarvisBrain.kt
│   │       ├── audio/AudioCoreManager.kt
│   │       ├── comms/
│   │       │   ├── CommsManager.kt
│   │       │   └── SocialMessagingManager.kt
│   │       ├── theme/ThemeManager.kt
│   │       └── ui/
│   │           ├── ArcReactorView.kt
│   │           └── EqualizerView.kt
├── companion/
│   └── ghost_desktop_companion.py
└── .github/workflows/
    └── build-apk.yml
```

---

## Running the Desktop Companion (Optional)

On your PC/laptop (Linux, macOS, or Windows):
```bash
python3 companion/ghost_desktop_companion.py --port 8080
```
Then enter your computer's local IP address under **CONFIG** in the Ghost Android app.
