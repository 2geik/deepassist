package com.deepassist.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.SystemClock
import android.util.Log
import com.deepassist.util.DiagLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speaks text that is still being written: [append] takes streamed deltas,
 * [SentenceSplitter] cuts them into sentences, and each one is spoken as soon as
 * it is complete. [finish] marks the end; [await] returns when everything was
 * said, [stop] cuts it all off at once.
 *
 * - While one sentence plays, the next one is already downloading, and all cloud
 *   audio of the answer goes through one [AudioTrack] at 1.1x, so there is no gap
 *   or click between sentences.
 * - When the cloud voice fails on a sentence, that sentence and the rest of the
 *   answer are read by [AndroidTts] — the voices don't alternate.
 * - Every sentence has its own time budget ([budgetMs]); a stalled one is dropped
 *   and the rest goes to the on-device voice.
 *
 * @param cloud null to use only the on-device voice.
 * @param silent voice output is off: sentences are only reported to [onSentence].
 * @param beforeFirstSound waits for a status phrase still playing before the first sentence.
 * @param onSentence a sentence is about to be spoken (the panel shows the text so far).
 * @param onFirstSound the first sound of this speaker started.
 */
class SentenceSpeaker(
    private val cloud: CloudTts?,
    private val androidTts: AndroidTts,
    scope: CoroutineScope,
    private val budgetMs: (String) -> Long,
    private val silent: Boolean = false,
    private val beforeFirstSound: suspend () -> Unit = {},
    private val onSentence: (String) -> Unit = {},
    private val onFirstSound: () -> Unit = {}
) {

    private val splitter = SentenceSplitter()
    private val sentences = Channel<String>(Channel.UNLIMITED)

    /** Rendezvous: the downloader can only be one sentence ahead of the player. */
    private val ready = Channel<Piece>()
    private val live = ConcurrentHashMap.newKeySet<Piece>()

    private val lock = Any()
    private var claimedAny = false
    private var cut = false

    @Volatile
    private var stopped = false

    @Volatile
    private var cloudOk = cloud != null && !silent

    @Volatile
    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var soundStarted = false

    private val job: Job = scope.launch(Dispatchers.IO) {
        try {
            coroutineScope {
                launch { download() }
                play()
            }
        } finally {
            live.forEach { it.cancel() }
            releaseTrack()
        }
    }

    /** Adds streamed text; complete sentences are queued for speaking. */
    fun append(delta: String) {
        if (cutOrStopped()) return
        splitter.push(delta).forEach { sentences.trySend(it) }
    }

    /** No more text: the rest is queued and the speaker ends after the last sentence. */
    fun finish() {
        if (!cutOrStopped()) splitter.flush().forEach { sentences.trySend(it) }
        sentences.close()
    }

    /** Suspends until everything queued was spoken (or [stop] was called). */
    suspend fun await() = job.join()

    /**
     * Keeps the sentence already taken for speaking (if any) and drops everything
     * else, for a reply that turned into a tool call. Returns true when a sentence
     * had been taken, i.e. the user hears (or is about to hear) the lead-in.
     */
    fun cutAfterCurrent(): Boolean {
        val started = synchronized(lock) {
            cut = true
            claimedAny
        }
        sentences.close()
        return started
    }

    /** Stops at once: the HTTP requests, the queue and the playing audio. */
    fun stop() {
        stopped = true
        synchronized(lock) { cut = true }
        sentences.close()
        live.forEach { it.cancel() }
        job.cancel()
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
        releaseTrack()
    }

    private fun cutOrStopped() = synchronized(lock) { cut || stopped }

    // ------------------------------------------------------------------

    private class Piece(val text: String, val synthesis: CloudTts.Synthesis?) {
        val audio = Channel<ByteArray>(Channel.UNLIMITED)

        /** The cloud gave no audio at all for this sentence. */
        @Volatile
        var failed = false

        /** The cloud stream broke after some audio. */
        @Volatile
        var broken = false

        fun cancel() {
            synthesis?.cancel()
            audio.cancel()
        }
    }

    private suspend fun download() = coroutineScope {
        try {
            for (text in sentences) {
                val synthesis = if (cloudOk) cloud?.synthesize(SpeechText.forVoice(text)) else null
                val piece = Piece(text, synthesis)
                live += piece
                if (synthesis != null) launch { fetch(piece, synthesis) } else piece.audio.close()
                ready.send(piece)
            }
        } finally {
            ready.close()
        }
    }

    private fun CoroutineScope.fetch(piece: Piece, synthesis: CloudTts.Synthesis) {
        var got = false
        try {
            val stream = synthesis.open()
            if (stream == null) {
                piece.failed = true
                return
            }
            stream.use {
                val buf = ByteArray(8192)
                while (isActive) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    if (piece.audio.trySend(buf.copyOf(n)).isFailure) break
                    got = true
                }
            }
        } catch (e: Exception) {
            if (got) piece.broken = true else piece.failed = true
        } finally {
            piece.audio.close()
        }
    }

    private suspend fun play() {
        var first = true
        for (piece in ready) {
            if (first) {
                first = false
                beforeFirstSound()
            }
            // Claiming and showing happen under the lock, so once cutAfterCurrent()
            // returns no further sentence starts or reaches the panel
            val claimed = synchronized(lock) {
                if (cut || stopped) {
                    false
                } else {
                    claimedAny = true
                    onSentence(piece.text)
                    true
                }
            }
            if (!claimed) {
                piece.cancel()
                live -= piece
                continue
            }
            when {
                silent -> piece.cancel()
                cloudOk && piece.synthesis != null -> playCloud(piece)
                else -> {
                    piece.cancel()
                    speakOnDevice(piece.text)
                }
            }
            live -= piece
        }
        drainTrack()
    }

    private suspend fun playCloud(piece: Piece) {
        var wrote = false
        val finished = withTimeoutOrNull(budgetMs(piece.text)) {
            var carry: Byte? = null
            for (bytes in piece.audio) {
                val data = carry?.let { byteArrayOf(it) + bytes } ?: bytes
                val even = data.size and 1.inv()
                carry = if (even < data.size) data[data.size - 1] else null
                if (even > 0 && !write(data, even)) return@withTimeoutOrNull false
                wrote = true
            }
            true
        }
        when {
            finished == null -> {
                Log.w(TAG, "sentence watchdog fired")
                DiagLog.event("Speech", "sentence watchdog fired")
                piece.cancel()
                switchToDevice()
                if (!wrote) speakOnDevice(piece.text)
            }
            finished == false -> Unit // track released by stop()
            piece.failed && !wrote -> {
                switchToDevice()
                speakOnDevice(piece.text)
            }
            // Broke mid-sentence: what was heard stays, the rest of the answer is on-device
            piece.broken -> switchToDevice()
        }
    }

    private fun switchToDevice() {
        if (!cloudOk) return
        cloudOk = false
        // While the quota/auth breaker is open every utterance lands here; it logged once already
        if (!OpenAiGate.isBlocked()) DiagLog.event("Speech", "cloud TTS failed, rest on-device")
    }

    /** Blocking write of the first [length] bytes; false when the track is gone. */
    private fun write(data: ByteArray, length: Int): Boolean {
        if (stopped) return false
        val t = track ?: createTrack()?.also { track = it } ?: return false
        // A fresh track, or one that ran dry waiting for the next sentence, starts with a
        // little silence: the output path wakes from standby / ramps up after an underrun
        // and would otherwise swallow the first syllable
        if (framesWritten == 0L || headPosition(t) >= framesWritten) {
            if (!writeRaw(t, LEAD_IN, LEAD_IN.size)) return false
        }
        if (!writeRaw(t, data, length)) return false
        if (!soundStarted) {
            soundStarted = true
            onFirstSound()
        }
        return true
    }

    private fun writeRaw(t: AudioTrack, data: ByteArray, length: Int): Boolean {
        var written = 0
        while (written < length) {
            val n = t.write(data, written, length - written)
            if (n <= 0) return false // released by stop()
            written += n
        }
        framesWritten += length / 2
        return true
    }

    private fun headPosition(t: AudioTrack): Long =
        runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(Long.MAX_VALUE)

    private suspend fun speakOnDevice(text: String) {
        drainTrack() // let the cloud audio already queued finish first
        if (stopped) return
        if (!soundStarted) {
            soundStarted = true
            onFirstSound()
        }
        val ok = withTimeoutOrNull(budgetMs(text)) { androidTts.speak(SpeechText.forVoice(text)) }
        if (ok == null) {
            Log.w(TAG, "on-device sentence watchdog fired")
            runCatching { androidTts.stop() }
        }
    }

    private fun createTrack(): AudioTrack? = runCatching {
        val minBuf = AudioTrack.getMinBufferSize(
            CloudTts.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(CloudTts.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuf * 2, 16384),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).also { t ->
            runCatching { t.playbackParams = PlaybackParams().setSpeed(PLAYBACK_SPEED) }
            framesWritten = 0L
            t.play()
        }
    }.onFailure { Log.w(TAG, "AudioTrack failed: ${it.javaClass.simpleName}") }.getOrNull()

    /** Waits until the audio written so far has been heard, then closes the track. */
    private suspend fun drainTrack() {
        val t = track ?: return
        if (!stopped) {
            runCatching { t.stop() } // MODE_STREAM: plays out what is buffered, then stops
            val head = { headPosition(t) }
            val remainingMs = (framesWritten - head()).coerceAtLeast(0) * 1000 / CloudTts.SAMPLE_RATE
            val deadline = SystemClock.elapsedRealtime() + (remainingMs / PLAYBACK_SPEED).toLong() + DRAIN_SLACK_MS
            while (!stopped && head() < framesWritten && SystemClock.elapsedRealtime() < deadline) delay(20)
        }
        releaseTrack()
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { t.release() }
    }

    companion object {
        private const val TAG = "SentenceSpeaker"
        private const val PLAYBACK_SPEED = 1.1f
        private const val DRAIN_SLACK_MS = 400L

        /** 120 ms of silence (24 kHz mono PCM16). */
        private val LEAD_IN = ByteArray(CloudTts.SAMPLE_RATE * 2 * 120 / 1000)
    }
}
