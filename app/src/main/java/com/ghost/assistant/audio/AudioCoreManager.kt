package com.ghost.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.sin

class AudioCoreManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var synthTrack: AudioTrack? = null
    private var isSynthesizing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    var isPlaying: Boolean = false
        private set

    var onStateChanged: ((Boolean) -> Unit)? = null
    var nowPlayingTitle: String = "Tactical Ambient Resonance (108 Hz)"
        private set

    fun playAmbientCore() {
        stop()
        nowPlayingTitle = "Tactical Ambient Resonance (108 Hz)"
        isSynthesizing = true
        isPlaying = true
        onStateChanged?.invoke(true)

        thread(name = "GhostAudioSynthThread") {
            val sampleRate = 44100
            val buffSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                synthTrack = track
                track.play()

                val buffer = ShortArray(buffSize / 2)
                var phase = 0.0
                val freq = 108.0 // Deep warm resonant harmonic
                val twoPiF = 2.0 * Math.PI * freq / sampleRate

                while (isSynthesizing) {
                    for (i in buffer.indices) {
                        buffer[i] = (sin(phase) * 6000.0).toInt().toShort()
                        phase += twoPiF
                        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                    }
                    track.write(buffer, 0, buffer.size)
                }

                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e("AudioCoreManager", "Synthesis error: ${e.message}", e)
            } finally {
                mainHandler.post {
                    if (!isPlaying) onStateChanged?.invoke(false)
                }
            }
        }
    }

    fun playLocalFile(uri: Uri, displayName: String) {
        stop()
        nowPlayingTitle = displayName
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, uri)
                setOnCompletionListener {
                    isPlaying = false
                    onStateChanged?.invoke(false)
                }
                prepare()
                start()
            }
            isPlaying = true
            onStateChanged?.invoke(true)
        } catch (e: Exception) {
            Log.e("AudioCoreManager", "Failed to play local audio: ${e.message}", e)
            stop()
        }
    }

    fun pause() {
        if (isSynthesizing) {
            isSynthesizing = false
            isPlaying = false
            onStateChanged?.invoke(false)
        } else if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            isPlaying = false
            onStateChanged?.invoke(false)
        }
    }

    fun resume() {
        if (mediaPlayer != null) {
            mediaPlayer?.start()
            isPlaying = true
            onStateChanged?.invoke(true)
        } else {
            playAmbientCore()
        }
    }

    fun stop() {
        isSynthesizing = false
        synthTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
            synthTrack = null
        }

        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }

        isPlaying = false
        onStateChanged?.invoke(false)
    }

    fun release() {
        stop()
    }
}
