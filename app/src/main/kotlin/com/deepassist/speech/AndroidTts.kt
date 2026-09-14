package com.deepassist.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Free, on-device TTS (android.speech.tts.TextToSpeech) — replaces the paid
 * OpenAI/Realtime audio synthesis. Forces Turkish and suspends the caller
 * until playback of the utterance finishes (or fails), same contract the
 * deleted OpenAI TTS client offered.
 */
class AndroidTts(context: Context) {

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            val engine = tts
            val ok = status == TextToSpeech.SUCCESS && engine != null &&
                engine.setLanguage(Locale("tr", "TR")) >= TextToSpeech.LANG_AVAILABLE
            ready.complete(ok)
        }
    }

    /** Synthesizes and plays [text] on-device; returns when playback finishes. */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        if (!ready.await()) return false
        val engine = tts ?: return false

        val utteranceId = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(true, onCancellation = null)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(false, onCancellation = null)
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && cont.isActive) cont.resume(false, onCancellation = null)
                }
            })
            cont.invokeOnCancellation { runCatching { engine.stop() } }
            val queued = engine.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued == TextToSpeech.ERROR && cont.isActive) {
                cont.resume(false, onCancellation = null)
            }
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
