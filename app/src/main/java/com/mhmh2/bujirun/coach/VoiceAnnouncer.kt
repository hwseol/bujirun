package com.mhmh2.bujirun.coach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks coaching lines through the phone's own TTS engine (no network, no cost).
 *
 * While a line is being spoken, other audio (the runner's music or podcast) is ducked rather
 * than paused - "transient, may duck" focus - and gets its volume back as soon as we're done.
 */
class VoiceAnnouncer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var ready = false
    private val beforeReady = mutableListOf<String>()
    private var speaking = 0 // utterances queued but not finished; touched on the main thread only
    private var releaseWhenIdle = false
    private var released = false
    private var nextId = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            beforeReady.clear()
            return
        }
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Korean TTS voice not available ($lang), using the engine default")
        }
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { finishedOne() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { finishedOne() } }
        })
        ready = true
        beforeReady.forEach(::speakNow)
        beforeReady.clear()
        if (releaseWhenIdle && speaking == 0) shutdown()
    }

    fun speak(text: String) {
        Log.d(TAG, "say: $text")
        if (released) return
        if (ready) speakNow(text) else beforeReady += text
    }

    private fun speakNow(text: String) {
        if (speaking == 0) audioManager.requestAudioFocus(focusRequest)
        speaking++
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u${nextId++}")
    }

    private fun finishedOne() {
        speaking = (speaking - 1).coerceAtLeast(0)
        if (speaking > 0) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (releaseWhenIdle) shutdown()
    }

    /** Lets anything still queued (e.g. the end-of-workout summary) finish, then frees the engine. */
    fun releaseWhenDone() {
        releaseWhenIdle = true
        if (ready && speaking == 0) shutdown()
    }

    private fun shutdown() {
        if (released) return
        released = true
        audioManager.abandonAudioFocusRequest(focusRequest)
        tts.shutdown()
    }

    private companion object {
        const val TAG = "Coach"
    }
}
