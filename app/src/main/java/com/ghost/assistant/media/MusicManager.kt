package com.ghost.assistant.media

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import java.net.URLEncoder

object MusicManager {

    enum class MediaControlAction {
        PLAY,
        PAUSE,
        PLAY_PAUSE,
        NEXT,
        PREVIOUS,
        STOP
    }

    /**
     * Handles "play [song/artist]" directives by:
     * 1. Attempting generic MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH for default music players.
     * 2. Checking and dispatching to Spotify (com.spotify.music) or YouTube Music (com.google.android.apps.youtube.music).
     * 3. Fallback to YouTube app search or web browser.
     */
    fun playFromSearch(context: Context, query: String, userName: String): String {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY)
            return "Resuming media playback for you, $userName."
        }

        val pm = context.packageManager

        // 1. Standard Android Media Play From Search intent
        val mediaSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, cleanQuery)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (mediaSearchIntent.resolveActivity(pm) != null) {
            try {
                context.startActivity(mediaSearchIntent)
                return "Playing $cleanQuery through your media player, $userName."
            } catch (_: Exception) {}
        }

        // 2. Check if Spotify is installed
        val spotifyPackage = "com.spotify.music"
        val spotifyLaunch = pm.getLaunchIntentForPackage(spotifyPackage)
        if (spotifyLaunch != null) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply {
                    setPackage(spotifyPackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(spotifyIntent)
                return "Searching and playing $cleanQuery on Spotify for you, $userName."
            } catch (_: Exception) {}
        }

        // 3. Check if YouTube Music is installed
        val ytmPackage = "com.google.android.apps.youtube.music"
        val ytmLaunch = pm.getLaunchIntentForPackage(ytmPackage)
        if (ytmLaunch != null) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val ytmIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")).apply {
                    setPackage(ytmPackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(ytmIntent)
                return "Playing $cleanQuery on YouTube Music, $userName."
            } catch (_: Exception) {}
        }

        // 4. Check if standard YouTube is installed
        val ytPackage = "com.google.android.youtube"
        val ytLaunch = pm.getLaunchIntentForPackage(ytPackage)
        if (ytLaunch != null) {
            try {
                val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(ytPackage)
                    putExtra("query", cleanQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(ytIntent)
                return "Searching for $cleanQuery on YouTube, $userName."
            } catch (_: Exception) {}
        }

        // 5. General browser fallback search
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            return "Searching for $cleanQuery online, $userName."
        } catch (_: Exception) {}

        return "Unable to route music playback for $cleanQuery, $userName."
    }

    /**
     * Controls active media playback (play, pause, next, previous, stop) via AudioManager media button broadcasts.
     */
    fun controlMedia(context: Context, action: MediaControlAction, userName: String): String {
        return when (action) {
            MediaControlAction.PLAY -> {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                "Resuming media playback, $userName."
            }
            MediaControlAction.PAUSE -> {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
                "Pausing media playback, $userName."
            }
            MediaControlAction.PLAY_PAUSE -> {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "Toggling playback, $userName."
            }
            MediaControlAction.NEXT -> {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                "Skipping to next track, $userName."
            }
            MediaControlAction.PREVIOUS -> {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Returning to previous track, $userName."
            }
            MediaControlAction.STOP -> {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_STOP)
                "Stopping media playback, $userName."
            }
        }
    }

    private fun sendMediaKeyEvent(context: Context, keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
