package com.mhmh2.bujirun.coach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Speaks coaching lines through the phone's own TTS engine (no network, no cost).
 *
 * While a line is being spoken, other audio (the runner's music or podcast) is ducked rather
 * than paused - "transient, may duck" focus - and gets its volume back as soon as we're done.
 */
class VoiceAnnouncer(context: Context) {
    private val cacheDir = context.cacheDir
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    /** The engine is up: lines are spoken as soon as they're given (the countdown waits for this). */
    var ready = false
        private set
    private val beforeReady = mutableListOf<Pair<String, Boolean>>()
    // Utterances queued but not finished, by id (main thread only). By id, not a count: a flush
    // drops queued ones whose "stopped" callbacks may or may not come later.
    private val pending = mutableSetOf<String>()
    private var urgentId: String? = null
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
            override fun onStart(utteranceId: String?) { Log.d(TAG, "speaking: $utteranceId") }
            override fun onDone(utteranceId: String?) {
                main.post { if (utteranceId == WARM_UP) warmedUp() else finishedOne(utteranceId) }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { finishedOne(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { finishedOne(utteranceId) } }
        })
        // The first line an engine speaks is slow (it loads the voice then): ~0.7 s, enough to put
        // "셋" behind the 3 on screen. Synthesize a word to a scratch file first, unheard; the
        // engine counts as ready once that's done (or after 1.5 s regardless).
        warmFile = File(cacheDir, "tts-warm.wav")
        val started = tts.synthesizeToFile("셋 둘 하나", Bundle(), warmFile, WARM_UP) == TextToSpeech.SUCCESS
        if (started) main.postDelayed(::warmedUp, 1_500) else warmedUp()
    }

    private var warmFile: File? = null

    private fun warmedUp() {
        if (ready || released) return
        warmFile?.delete()
        ready = true
        beforeReady.forEach { (text, urgent) -> speakNow(text, urgent) }
        beforeReady.clear()
        if (releaseWhenIdle && pending.isEmpty()) shutdown()
    }

    /**
     * [urgent]: said now, cutting off whatever routine line is still being spoken (the countdown,
     * "turn right now") - a turn said late is a turn missed. Never cuts off another urgent line.
     */
    fun speak(text: String, urgent: Boolean = false) {
        if (released) return
        Log.d(TAG, "say u$nextId: $text")
        if (ready) speakNow(text, urgent) else beforeReady += text to urgent
    }

    private fun speakNow(text: String, urgent: Boolean) {
        if (pending.isEmpty()) audioManager.requestAudioFocus(focusRequest)
        val id = "u${nextId++}"
        val flush = urgent && urgentId !in pending
        if (flush) pending.clear()
        pending += id
        if (urgent) urgentId = id
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun finishedOne(id: String?) {
        if (id == null || !pending.remove(id) || pending.isNotEmpty()) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (releaseWhenIdle) shutdown()
    }

    /** Lets anything still queued (e.g. the end-of-workout summary) finish, then frees the engine. */
    fun releaseWhenDone() {
        releaseWhenIdle = true
        if (ready && pending.isEmpty()) shutdown()
    }

    private fun shutdown() {
        if (released) return
        released = true
        audioManager.abandonAudioFocusRequest(focusRequest)
        tts.shutdown()
    }

    private companion object {
        const val TAG = "Coach"
        const val WARM_UP = "warm-up"
    }
}
