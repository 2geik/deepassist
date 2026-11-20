package com.deepassist.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.deepassist.service.VoiceActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android's own [SpeechRecognizer] — the fallback when OpenAI realtime STT is
 * unavailable (no credit, bad key, no connection). Returns the same results as
 * [RealtimeStt] so the orchestrator treats both alike.
 *
 * Recognizer choice: the on-device recognizer when it has Turkish, otherwise an
 * installed [RecognitionService] picked by component. The default recognizer is
 * never used blindly: this app registers a stub RecognitionService for the
 * assistant role, and resolving to it would make us call ourselves.
 */
class AndroidStt(context: Context) {

    private val appContext = context.applicationContext

    /** Cached result of the on-device Turkish check; null until checked. */
    @Volatile
    private var onDeviceTurkish: Boolean? = null

    private sealed class Recognizer {
        object OnDevice : Recognizer() {
            override fun toString() = "on-device"
        }

        data class Service(val component: ComponentName) : Recognizer() {
            override fun toString() = component.packageName
        }
    }

    /** False when the phone has no usable recognizer besides our own stub. */
    fun isAvailable(): Boolean = serviceCandidates().isNotEmpty() || onDeviceSupported()

    /**
     * Listens for one utterance. Partial hypotheses go to [onPartial] (each one is the
     * whole text so far). Tries the next recognizer when one fails before the user spoke.
     */
    suspend fun listen(onPartial: (String) -> Unit): RealtimeSttResult {
        val candidates = buildList {
            if (onDeviceUsable()) add(Recognizer.OnDevice)
            serviceCandidates().forEach { add(Recognizer.Service(it)) }
        }
        var last: RealtimeSttResult = RealtimeSttResult.Unavailable()
        for (candidate in candidates) {
            var attempt = listenWith(candidate, onPartial)
            // The first on-device session after the app starts drops its connection at
            // once (error 11), and an immediate retry finds it busy (error 8): the service
            // is still unbinding from the support check. Give it a moment and retry instead
            // of starting a second recognizer, which plays its own start sound on top.
            var retries = 0
            while (retries < MAX_RETRIES && !attempt.heardSpeech && attempt.error in RETRYABLE) {
                retries++
                Log.i(TAG, "recognizer $candidate error ${attempt.error}, retry $retries")
                delay(RETRY_DELAY_MS)
                attempt = listenWith(candidate, onPartial)
            }
            Log.i(TAG, "recognizer $candidate → ${attempt.result.javaClass.simpleName}")
            if (attempt.result !is RealtimeSttResult.Unavailable || attempt.heardSpeech) {
                return attempt.result
            }
            if (candidate == Recognizer.OnDevice && attempt.languageError) onDeviceTurkish = false
            last = attempt.result
        }
        return last
    }

    private class Attempt(
        val result: RealtimeSttResult,
        val heardSpeech: Boolean = false,
        val languageError: Boolean = false,
        val error: Int? = null
    )

    private suspend fun listenWith(
        candidate: Recognizer,
        onPartial: (String) -> Unit
    ): Attempt = withContext(Dispatchers.Main) {
        // SpeechRecognizer must be created and driven on the main thread
        val recognizer = runCatching {
            when (candidate) {
                Recognizer.OnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                is Recognizer.Service -> SpeechRecognizer.createSpeechRecognizer(appContext, candidate.component)
            }
        }.getOrNull() ?: return@withContext Attempt(RealtimeSttResult.Unavailable())

        var heardSpeech = false
        var partial = ""
        var cont: CancellableContinuation<Attempt>? = null
        val timers = mutableListOf<Job>()

        fun finish(attempt: Attempt) {
            val c = cont ?: return
            cont = null
            if (c.isActive) c.resume(attempt, onCancellation = null)
        }

        fun finalOrNoSpeech(): RealtimeSttResult =
            if (partial.isNotBlank()) RealtimeSttResult.Final(partial.trim()) else RealtimeSttResult.NoSpeech

        try {
            coroutineScope {
                val attempt = suspendCancellableCoroutine { c ->
                    cont = c
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}

                        override fun onBeginningOfSpeech() {
                            heardSpeech = true
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            // Roughly -2..10 dB → the PCM peak scale the level bars expect
                            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                            VoiceActivity.reportPeak((level * 9000f).toInt())
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            val result = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finalOrNoSpeech()
                                else -> RealtimeSttResult.Unavailable(OpenAiFailure.OTHER, heardSpeech)
                            }
                            Log.i(TAG, "recognizer $candidate error $error")
                            finish(Attempt(result, heardSpeech, languageError = isLanguageError(error), error = error))
                        }

                        override fun onResults(results: Bundle?) {
                            val text = results.firstResult()?.trim().orEmpty().ifBlank { partial.trim() }
                            val result = if (text.isBlank()) RealtimeSttResult.NoSpeech else RealtimeSttResult.Final(text)
                            finish(Attempt(result, heardSpeech))
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val text = partialResults.firstResult()?.trim().orEmpty()
                            if (text.isNotBlank()) {
                                heardSpeech = true
                                partial = text
                                onPartial(text)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    // Same limits as RealtimeStt: 6 s to start talking, 15 s in all
                    timers += launch {
                        delay(NO_SPEECH_TIMEOUT_MS)
                        if (!heardSpeech) finish(Attempt(RealtimeSttResult.NoSpeech))
                    }
                    timers += launch {
                        delay(OVERALL_TIMEOUT_MS)
                        finish(Attempt(finalOrNoSpeech(), heardSpeech))
                    }
                    try {
                        recognizer.startListening(recognizerIntent())
                    } catch (e: Exception) {
                        Log.w(TAG, "startListening failed on $candidate: ${e.javaClass.simpleName}")
                        finish(Attempt(RealtimeSttResult.Unavailable()))
                    }
                }
                timers.forEach { it.cancel() }
                attempt
            }
        } finally {
            cont = null
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
    }

    // ------------------------------------------------------------------
    // Recognizer selection
    // ------------------------------------------------------------------

    private fun onDeviceSupported(): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)

    /** On-device recognizer with Turkish. On API 33+ the language is checked once per process. */
    private suspend fun onDeviceUsable(): Boolean {
        if (!onDeviceSupported()) return false
        if (Build.VERSION.SDK_INT < 33) return onDeviceTurkish != false
        onDeviceTurkish?.let { return it }
        val supported = checkTurkishOnDevice()
        onDeviceTurkish = supported
        Log.i(TAG, "on-device Turkish recognition: $supported")
        return supported
    }

    private suspend fun checkTurkishOnDevice(): Boolean = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33) return@withContext false
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
            .getOrNull() ?: return@withContext false
        try {
            withTimeoutOrNull(SUPPORT_CHECK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    recognizer.checkRecognitionSupport(
                        recognizerIntent(),
                        appContext.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                val ok = support.installedOnDeviceLanguages.any { isTurkish(it) }
                                if (cont.isActive) cont.resume(ok, onCancellation = null)
                            }

                            override fun onError(error: Int) {
                                if (cont.isActive) cont.resume(false, onCancellation = null)
                            }
                        }
                    )
                }
            } ?: false
        } catch (e: Exception) {
            false
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    /**
     * Installed recognition services except our own stub: the system default first
     * (when it isn't us), then Google's, then anything else.
     */
    private fun serviceCandidates(): List<ComponentName> {
        val pm = appContext.packageManager
        val installed = runCatching {
            pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.let { si -> ComponentName(si.packageName, si.name) } }
        }.getOrDefault(emptyList())
            .filter { it.packageName != appContext.packageName }
        val default = runCatching {
            Settings.Secure.getString(appContext.contentResolver, "voice_recognition_service")
                ?.let { ComponentName.unflattenFromString(it) }
        }.getOrNull()
        return installed.sortedBy { c ->
            when {
                c == default -> 0
                c.packageName == "com.google.android.tts" -> 1
                c.packageName == "com.google.android.as" -> 2
                c.packageName.startsWith("com.google.") -> 3
                else -> 4
            }
        }
    }

    private fun isLanguageError(error: Int): Boolean =
        Build.VERSION.SDK_INT >= 31 && (
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            )

    private fun isTurkish(tag: String) = tag.equals(LANGUAGE, ignoreCase = true) || tag.equals("tr", ignoreCase = true)

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        private const val TAG = "AndroidStt"
        private const val LANGUAGE = "tr-TR"
        private const val NO_SPEECH_TIMEOUT_MS = 6_000L
        private const val OVERALL_TIMEOUT_MS = 15_000L
        private const val SUPPORT_CHECK_TIMEOUT_MS = 2_000L
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 400L
        private val RETRYABLE = setOf(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }
}
