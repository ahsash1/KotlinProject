package org.example.project.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper around [TextToSpeech]. Queues anything spoken before init
 * finishes; swallows all failures since speech is a nice-to-have that must
 * never block or crash guidance.
 */
class SpeechController(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                try {
                    tts?.language = Locale.getDefault()
                } catch (t: Throwable) {
                    // Some locales aren't installed; default voice is fine.
                }
                while (pending.isNotEmpty()) {
                    speakInternal(pending.removeFirst())
                }
            } else {
                Log.w(TAG, "TTS init failed, status=$status")
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (ready) speakInternal(text) else pending.addLast(text)
    }

    private fun speakInternal(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guidance")
        } catch (t: Throwable) {
            Log.w(TAG, "speak() failed", t)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown() failed", t)
        } finally {
            tts = null
        }
    }

    companion object {
        private const val TAG = "SpeechController"
    }
}
