package org.example.project.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Captures one spoken utterance via [SpeechRecognizer]. Callback-based, and
 * guarantees exactly one call to [listenOnce]'s callback per invocation --
 * "no match" is a value (null), never a silent no-op.
 */
class VoiceTaskRecognizer(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** [onResult] receives the transcript, or null on failure/no speech/not available. */
    fun listenOnce(onResult: (String?) -> Unit) {
        if (!isAvailable()) {
            onResult(null)
            return
        }

        destroy() // guard against a stray prior session still active

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        var answered = false
        fun complete(text: String?) {
            if (answered) return
            answered = true
            onResult(text)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                complete(matches?.firstOrNull())
            }

            override fun onError(error: Int) {
                Log.w(TAG, "recognition error=$error")
                complete(null)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
            complete(null)
        }
    }

    /** Safe to call even if no session is active. */
    fun destroy() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (t: Throwable) {
            // Best-effort cleanup; nothing to recover.
        } finally {
            recognizer = null
        }
    }

    companion object {
        private const val TAG = "VoiceTaskRecognizer"
    }
}
