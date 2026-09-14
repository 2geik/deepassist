package com.deepassist.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Free, on-device TTS (android.speech.tts.TextToSpeech) — the fallback when
 * OpenAI TTS is unavailable. Forces Turkish and suspends the caller until
 * playback of the utterance finishes (or fails).
 *
 * Every utterance is tracked by id so each caller resumes exactly once —
 * including when it is flushed by a newer utterance or by [stop], which the
 * engine reports through onStop rather than onDone.
 */
class AndroidTts(context: Context) {

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) = finish(id, true)

        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) = finish(id, false)
        override fun onError(id: String?, errorCode: Int) = finish(id, false)
        override fun onStop(id: String?, interrupted: Boolean) = finish(id, false)
    }

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            val engine = tts
            val ok = status == TextToSpeech.SUCCESS && engine != null &&
                engine.setLanguage(Locale("tr", "TR")) >= TextToSpeech.LANG_AVAILABLE
            ready.complete(ok)
        }.also { it.setOnUtteranceProgressListener(listener) }
    }

    /** Synthesizes and plays [text] on-device; returns when playback finishes. */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        // Engine binding can silently never complete on some ROMs
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return false
        val engine = tts ?: return false

        val utteranceId = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            pending[utteranceId] = cont
            cont.invokeOnCancellation {
                pending.remove(utteranceId)
                runCatching { engine.stop() }
            }
            val queued = engine.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued == TextToSpeech.ERROR) finish(utteranceId, false)
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        // Not every engine reports onStop after stop(); release waiting callers ourselves
        pending.keys.toList().forEach { finish(it, false) }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun finish(id: String?, ok: Boolean) {
        val cont = id?.let { pending.remove(it) } ?: return
        if (cont.isActive) cont.resume(ok, onCancellation = null)
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 3_000L
    }
}
