package com.deepassist.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.deepassist.BuildConfig
import com.deepassist.MainActivity
import com.deepassist.R
import com.deepassist.SessionActivity
import com.deepassist.data.ChatHistoryStore
import com.deepassist.data.ConversationManager
import com.deepassist.data.MemoryExtractor
import com.deepassist.data.SecureStore
import com.deepassist.data.ToolCall
import com.deepassist.data.ToolResult
import com.deepassist.data.WhatsNew
import com.deepassist.llm.AccumulatingToolCall
import com.deepassist.llm.DeepSeekClient
import com.deepassist.llm.SystemPrompt
import com.deepassist.llm.ToolParser
import com.deepassist.speech.AndroidTts
import com.deepassist.speech.Earcons
import com.deepassist.speech.OpenAiTts
import com.deepassist.speech.RealtimeStt
import com.deepassist.speech.RealtimeSttResult
import com.deepassist.speech.SpeechToText
import com.deepassist.agent.ScreenAgent
import com.deepassist.camera.LastPhoto
import com.deepassist.tools.EndConversationTool
import com.deepassist.tools.Tool
import com.deepassist.tools.ToolRegistry
import com.deepassist.util.DiagLog
import com.deepassist.util.Haptics
import com.deepassist.util.PermissionsHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Central orchestrator — cascaded STT → DeepSeek LLM → Android TTS.
 *
 * Flow (per turn):
 *   listenAndTranscribe() → DeepSeek streamChat() → (tool calls? execute,
 *   feed results back, repeat) → speak the final answer → reopen mic for
 *   a follow-up command.
 */
class AssistantForegroundService : Service() {

    // An exception escaping a coroutine must not kill the process: a crash also
    // knocks out the accessibility service, and Android doesn't rebind it by itself
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught coroutine error", e)
        DiagLog.event("Service", "coroutine error ${DiagLog.describe(e)}")
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

    private lateinit var secureStore: SecureStore
    private lateinit var conversation: ConversationManager
    private lateinit var toolRegistry: ToolRegistry

    private lateinit var deepSeek: DeepSeekClient
    private lateinit var memoryExtractor: MemoryExtractor
    private lateinit var openAiTts: OpenAiTts
    private lateinit var androidTts: AndroidTts
    private lateinit var earcons: Earcons

    // STT: realtime streaming session is the primary path, multipart is the fallback
    private lateinit var realtimeStt: RealtimeStt
    private lateinit var stt: SpeechToText

    @Volatile private var currentSessionJob: Job? = null
    @Volatile private var currentChatId: String? = null
    private lateinit var chatHistory: ChatHistoryStore

    @Volatile private var audioFocusGranted = false
    @Volatile private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    private val listening = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)

    @Volatile private var pendingQuestion: CancellableContinuation<String?>? = null
    @Volatile private var pendingListenJob: Job? = null
    @Volatile private var lastFgsType = -1
    @Volatile private var phraseJob: Job? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    @Volatile private var hadError = false

    // Bumped per trigger so a cancelled session's cleanup can't clobber its successor
    private val sessionGeneration = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.event("Service", "onCreate")
        createNotificationChannel()
        secureStore = SecureStore(this)
        notifyStartupStatus()

        conversation = ConversationManager()
        deepSeek = DeepSeekClient { secureStore.deepseekApiKey }
        memoryExtractor = MemoryExtractor(this, deepSeek)
        openAiTts = OpenAiTts { secureStore.openAiApiKey }
        androidTts = AndroidTts(this)
        earcons = Earcons(this)
        realtimeStt = RealtimeStt { secureStore.openAiApiKey }
        stt = SpeechToText { secureStore.openAiApiKey }
        toolRegistry = ToolRegistry({ question -> askUser(question) }, deepSeek)
        Tool.playEarcon = { earcons.play(it) }
        Tool.say = { text -> awaitPhrase(); speak(text) }
        Tool.sayQuick = { text -> androidTts.speak(text, volume = CUE_VOLUME) }
        toolRegistry.initialize(this)
        chatHistory = ChatHistoryStore.get(this)

        // Media control tools need to release audio focus before dispatching
        // media key events so the events route to the actual music app.
        Tool.releaseAudioFocusForMediaControl = {
            abandonAudioFocus()
        }

        // Power button (screen off) definitively ends any running session.
        // Registered on the service so it works even when SessionActivity
        // couldn't launch (e.g. lock-screen restrictions on HyperOS).
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF && currentSessionJob?.isActive == true) {
                    // Only a session the user could see was ended on purpose. Ending a
                    // headless one must not swallow the second press of a new trigger.
                    if (SessionActivity.isShowing()) lastScreenOffEndAt = SystemClock.elapsedRealtime()
                    DiagLog.event("Session", "ended by screen off")
                    endCurrentSession()
                }
            }
        }
        screenOffReceiver = receiver
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }

        AccessibilitySvc.onPowerKeyDetected = { handleTrigger() }
        AccessibilitySvc.onStateChanged = { updateNotification(computeStatusText()) }
        // A stalled accessibility service only shows once the grace period is over
        serviceScope.launch {
            delay(AccessibilitySvc.CONNECT_GRACE_MS + 2_000L)
            updateNotification(computeStatusText())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagLog.event("Service", "start ${intent?.action?.substringAfterLast('.') ?: "sticky"}")
        when (intent?.action) {
            ACTION_VOICE_TRIGGER, ACTION_POWER_TRIGGER, ACTION_TAP_TRIGGER ->
                handleTrigger(intent?.getStringExtra(EXTRA_RESUME_CHAT_ID))
            // Debug builds only: adb types the first request instead of the microphone
            ACTION_DEBUG_PROMPT -> if (BuildConfig.DEBUG) {
                handleTrigger(debugPrompt = intent?.getStringExtra(EXTRA_DEBUG_TEXT))
            }
            ACTION_END_SESSION -> endCurrentSession()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> notifyStartupStatus()
        }
        return START_STICKY
    }

    // ==================================================================
    // Trigger — session entry point
    // ==================================================================

    private fun handleTrigger(resumeChatId: String? = null, debugPrompt: String? = null) {
        // An open ask_user question: the trigger means "I'm answering now"
        if (pendingQuestion != null && !listening.get()) {
            Log.i(TAG, "trigger: answering pending question")
            DiagLog.event("Session", "trigger answers pending question")
            SessionActivity.relaunchSuppressed = false
            pendingListenJob = serviceScope.launch { listenForPendingAnswer() }
            return
        }

        // A trigger must always produce a listening assistant. The user can't see a
        // stuck or half-finished session, so restart it instead of ignoring the gesture.
        val previous = currentSessionJob
        if (previous?.isActive == true || listening.get()) {
            Log.i(TAG, "trigger while busy: restarting session")
            endCurrentSession()
        }
        val generation = sessionGeneration.incrementAndGet()
        listening.set(true)
        processing.set(true)
        uiHandedOff = false
        sessionActive = true
        Log.i(TAG, "session $generation starting")
        DiagLog.event("Session", "$generation start")

        currentSessionJob = serviceScope.launch {
            // Let the cancelled session release the microphone and TTS first
            previous?.let { withTimeoutOrNull(PREVIOUS_SESSION_JOIN_MS) { it.join() } }
            val wakeLock = acquireWakeLock()
            val sessionStartedAt = System.currentTimeMillis()
            var sessionChatId: String? = null
            try {
                // Her tetikleme yepyeni bir sohbettir; oturum içindeki takip
                // komutları ise bağlamı korur
                conversation.clear()
                currentChatId = resumeChatId
                val resumeRecord = resumeChatId?.let { chatHistory.load(it) }
                val resumeTranscript = resumeRecord?.let { rec ->
                    rec.messages.takeLast(30).joinToString("\n") {
                        (if (it.role == "user") "Kullanıcı: " else "Asistan: ") + it.text
                    }.take(4000)
                }
                conversation.setSystemPrompt(
                    SystemPrompt.build(this@AssistantForegroundService, resumeTranscript)
                )

                if (PermissionsHelper.hasOverlay(this@AssistantForegroundService)) {
                    OverlayService.newSession(this@AssistantForegroundService, "Dinliyorum...")
                }
                if (resumeRecord != null) {
                    val historyMessages = resumeRecord.messages.map { (it.role == "user") to it.text }
                    OverlayService.seedHistory(this@AssistantForegroundService, historyMessages)
                }
                // With the panel on screen the app counts as in use, so Android grants the
                // microphone type even when the service itself was restarted in the background
                awaitPanel()
                startForegroundCompat(buildNotification("Dinliyorum..."))
                requestAudioFocus()
                warnIfPowerTriggerBroken()

                var turn = 0
                var sessionEnded = false
                while (isActive && turn < MAX_CONVERSATION_TURNS && !sessionEnded) {
                    turn++
                    runCatching { wakeLock?.acquire(180_000L) }
                    val text = if (turn == 1 && !debugPrompt.isNullOrBlank()) {
                        debugPrompt
                    } else {
                        startListeningFeedback("Dinliyorum...")
                        listenAndTranscribe().also { earcon(open = false) }
                    }

                    if (text.isNullOrBlank()) {
                        if (turn == 1) {
                            val hint = if (!secureStore.hasOpenAiKey()) {
                                "Bu sürüm OpenAI anahtarı olmadan derlenmiş, seni duyamıyorum."
                            } else if (!secureStore.hasDeepSeekKey()) {
                                "Bu sürüm DeepSeek anahtarı olmadan derlenmiş, cevap veremiyorum."
                            } else {
                                "Seni anlayamadım, tekrar dener misin?"
                            }
                            overlayAssistant(hint)
                            speakAsync(hint)
                        } else {
                            // Follow-up window stayed silent — close quietly
                            OverlayService.hide(this@AssistantForegroundService)
                        }
                        sessionEnded = true
                        break
                    }

                    overlayUser(text)
                    val chatId = currentChatId ?: chatHistory.create(text).id
                    currentChatId = chatId
                    sessionChatId = chatId
                    runCatching { chatHistory.append(chatId, "user", text) }

                    if (isEndPhrase(text)) {
                        finishConversation("Görüşürüz!")
                        sessionEnded = true
                        break
                    }
                    if (processUserPrompt(text)) {
                        sessionEnded = true
                        break // conversation over (farewell or error)
                    }
                    // A bare "teşekkürler" closes the conversation once the reply is spoken,
                    // whether or not the model chose to call end_conversation
                    if (isThanksOnly(text)) {
                        finishConversation(null)
                        sessionEnded = true
                        break
                    }
                    // A tool opened another app (chat, video, dialer) that now owns the
                    // screen and audio — don't reopen the mic or pull the panel over it
                    if (uiHandedOff) {
                        Log.i(TAG, "session $generation handed off to another app — closing")
                        sessionEnded = true
                        break
                    }
                    // Once per release, after the first finished request: what's new
                    if (WhatsNew.shouldSpeak(this@AssistantForegroundService)) {
                        WhatsNew.markSpoken(this@AssistantForegroundService)
                        DiagLog.event("Session", "spoke what's new")
                        speak(WhatsNew.SPOKEN)
                    }
                    // Answer done → reopen the microphone for a follow-up command
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. the microphone refused to start — say so instead of going silent
                Log.e(TAG, "session $generation failed", e)
                DiagLog.event("Session", "$generation error ${DiagLog.describe(e)}")
                runCatching { speak("Üzgünüm, bir sorun oluştu. Tekrar dener misin?") }
            } finally {
                // A newer session may already own the shared state — leave it alone
                if (sessionGeneration.get() == generation) {
                    abandonAudioFocus()
                    listening.set(false)
                    processing.set(false)
                    sessionActive = false
                    VoiceActivity.set(VoiceActivity.Phase.IDLE)
                    LastPhoto.clear()
                    currentChatId = null
                    if (!hadError) updateNotification(computeStatusText())
                    hadError = false
                }
                wakeLock?.let { runCatching { it.release() } }
                // Learn durable facts from this session in the background, however it ended
                sessionChatId?.let { chatId ->
                    serviceScope.launch {
                        runCatching { memoryExtractor.extract(chatId, sessionStartedAt) }
                            .onFailure { Log.w(TAG, "memory extraction failed", it) }
                    }
                }
                Log.i(TAG, "session $generation finished")
                DiagLog.event("Session", "$generation finished")
            }
        }
    }

    // ==================================================================
    // STT
    // ==================================================================

    private suspend fun listenAndTranscribe(): String? {
        if (!PermissionsHelper.hasMicrophone(this)) return null

        // Primary path: realtime streaming STT — audio uploads while the user
        // is still talking and the transcript types into the overlay live
        if (secureStore.hasOpenAiKey()) {
            when (val result = realtimeStt.listen { partial -> overlayUserPartial(partial) }) {
                is RealtimeSttResult.Final -> return result.text
                is RealtimeSttResult.NoSpeech -> return null
                is RealtimeSttResult.Unavailable -> Unit // fall back to multipart below
            }
        }

        val audio = recordAudio() ?: return null
        updateNotification("Anlıyorum...")
        overlayStatus("Anlıyorum...")
        val text = stt.transcribe(audio, "tr")
        audio.delete()
        return text
    }

    /** STT for ask_user — primary path: realtime streaming websocket,
     *  fallback: multipart to /v1/audio/transcriptions. */
    private suspend fun transcribeWithFallback(): String? {
        if (!PermissionsHelper.hasMicrophone(this)) return null
        if (secureStore.hasOpenAiKey()) {
            when (val result = realtimeStt.listen { /* no partial — question asked */ }) {
                is RealtimeSttResult.Final -> return result.text
                is RealtimeSttResult.NoSpeech -> return null
                is RealtimeSttResult.Unavailable -> Unit // fall through
            }
        }
        val audio = recordAudio() ?: return null
        val text = stt.transcribe(audio, "tr")
        audio.delete()
        return text
    }

    /** Records 16kHz mono PCM16; stops after 3s of silence or 8s total. Fallback only. */
    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): File? = withContext(Dispatchers.IO) {
        if (!PermissionsHelper.hasMicrophone(this@AssistantForegroundService)) return@withContext null

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return@withContext null

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, sampleRate) // ≥0.5s buffer
            )
        } catch (e: Exception) {
            return@withContext null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext null
        }

        val pcm = ByteArrayOutputStream()
        val buffer = ShortArray(sampleRate / 10) // 100ms chunks
        var silenceMs = 0L
        var heardSpeech = false
        val start = SystemClock.elapsedRealtime()

        try {
            recorder.startRecording()
            while (SystemClock.elapsedRealtime() - start < MAX_RECORD_MS) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break

                var maxAmp = 0
                val bytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    val sample = buffer[i].toInt()
                    val amp = abs(sample)
                    if (amp > maxAmp) maxAmp = amp
                    bytes[i * 2] = (sample and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
                pcm.write(bytes)

                VoiceActivity.reportPeak(maxAmp)
                val chunkMs = read * 1000L / sampleRate
                if (maxAmp < SILENCE_THRESHOLD) {
                    silenceMs += chunkMs
                    if (silenceMs >= SILENCE_STOP_MS) break
                } else {
                    heardSpeech = true
                    silenceMs = 0
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        if (!heardSpeech) return@withContext null
        val pcmBytes = pcm.toByteArray()
        if (pcmBytes.size < sampleRate / 5 * 2) return@withContext null // <200ms of audio

        val wav = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
        writeWav(wav, pcmBytes, sampleRate, channels = 1, bitsPerSample = 16)
        wav
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        file.outputStream().use {
            it.write(header.array())
            it.write(pcm)
        }
    }

    // ==================================================================
    // LLM pipeline
    // ==================================================================

    /** @return true when the conversation ended (farewell or error) — stop the listen loop. */
    private suspend fun processUserPrompt(text: String): Boolean {
        processing.set(true)
        VoiceActivity.set(VoiceActivity.Phase.THINKING)
        try {
            updateNotification("Düşünüyorum...")
            overlayStatus("Düşünüyorum...")
            conversation.addUserMessage(text)

            var streamError: String? = null
            var completedToolCalls: List<AccumulatingToolCall>? = null
            var toolReasoning: String? = null
            var spokeThinking = false
            val content = StringBuilder()

            deepSeek.streamChat(conversation.getMessages(), toolRegistry.getDefinitions())
                .collect { event ->
                    when {
                        event.error != null -> streamError = event.error

                        event.finishReason == "tool_calls" -> {
                            completedToolCalls = event.toolCalls
                            toolReasoning = event.reasoning
                        }

                        event.finishReason == "stop" -> Unit

                        // Early tool-call signal: speak the thinking phrase immediately,
                        // before the tool arguments finish streaming
                        event.toolCalls != null -> {
                            if (!spokeThinking) {
                                val toolName = event.toolCalls.firstOrNull()?.name
                                val phrase = toolName?.let { toolRegistry.get(it)?.thinkingPhrase }
                                if (!phrase.isNullOrBlank()) {
                                    spokeThinking = true
                                    updateNotification(phrase)
                                    overlayStatus(phrase)
                                    speakAsync(phrase)
                                }
                            }
                        }

                        event.contentDelta != null -> content.append(event.contentDelta)
                    }
                }

            val error = streamError
            if (error != null) {
                handleError(error)
                return true
            }

            val toolCalls = completedToolCalls
            return if (!toolCalls.isNullOrEmpty()) {
                runAgentLoop(toolCalls.map { it.toToolCall() }, spokeThinking, toolReasoning)
            } else {
                val answer = content.toString().trim().ifBlank { "Bir cevap üretemedim." }
                conversation.addAssistantMessage(answer)
                showAndSpeak(answer)
                false
            }
        } catch (e: CancellationException) {
            throw e // session ended by the user — not an error
        } catch (e: Exception) {
            handleError(e.message ?: "Bilinmeyen hata")
            return true
        } finally {
            processing.set(false)
        }
    }

    /**
     * Executes tool calls and feeds results back to DeepSeek. The follow-up
     * completion may request further tools (e.g. search_contacts → make_phone_call),
     * so this loops until a text answer arrives or MAX_TOOL_ROUNDS is hit.
     *
     * @return true when the conversation ended (end_conversation or error).
     */
    private suspend fun runAgentLoop(
        initialToolCalls: List<ToolCall>,
        firstPhraseSpoken: Boolean,
        initialReasoning: String?
    ): Boolean {
        var toolCalls = initialToolCalls
        // deepseek-flash thinks by default and answers HTTP 400 to a tool follow-up
        // whose tool-call message lacks the reasoning that produced it
        var reasoning = initialReasoning
        var phraseSpoken = firstPhraseSpoken
        var round = 0

        while (round < MAX_TOOL_ROUNDS) {
            round++

            // end_conversation terminates the session instead of round-tripping
            val endCall = toolCalls.firstOrNull { it.function?.name == "end_conversation" }
            if (endCall != null) {
                val args = ToolParser.parseToolCallArguments(endCall.function?.arguments ?: "")
                finishConversation(EndConversationTool.farewellFrom(args))
                return true
            }

            conversation.addAssistantMessage(null, toolCalls, reasoning)
            val results = executeToolCalls(toolCalls, phraseSpoken)
            conversation.addMessages(ToolParser.buildToolCallMessages(toolCalls, results))

            // Tools whose result is the answer itself skip the extra DeepSeek round
            directAnswer(toolCalls, results)?.let { answer ->
                conversation.addAssistantMessage(answer)
                showAndSpeak(answer)
                return false
            }

            updateNotification("Cevap hazırlanıyor...")
            overlayStatus("Cevap hazırlanıyor...")
            val reply = deepSeek.complete(conversation.getMessages(), toolRegistry.getDefinitions())
            if (reply == null) {
                handleError("DeepSeek'ten cevap alınamadı.")
                return true
            }

            val nextCalls = reply.tool_calls
            if (nextCalls.isNullOrEmpty()) {
                val answer = reply.content?.trim().takeUnless { it.isNullOrBlank() } ?: "İşlem tamamlandı."
                conversation.addAssistantMessage(answer)
                showAndSpeak(answer)
                return false
            }
            toolCalls = nextCalls
            reasoning = reply.reasoning_content
            phraseSpoken = false // each round announces its own first tool
        }
        handleError("İşlem çok fazla adım gerektirdi ve yarıda kesildi.")
        return true
    }

    /**
     * The spoken answer when every call of the round returned [ToolResult.speakDirectly],
     * else null. The history stays valid: assistant(tool_calls) → tool → assistant(text),
     * the same shape as a normal final answer.
     */
    private fun directAnswer(toolCalls: List<ToolCall>, results: Map<String, ToolResult>): String? {
        val callResults = toolCalls.map { call -> call.id?.let { results[it] } ?: return null }
        if (callResults.isEmpty() || callResults.any { !it.speakDirectly }) return null
        return callResults
            .map { r -> if (r.success) r.data else (r.error ?: r.data) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { null }
    }

    // ==================================================================
    // Tool execution
    // ==================================================================

    private suspend fun executeToolCalls(
        toolCalls: List<ToolCall>,
        phraseAlreadySpoken: Boolean
    ): Map<String, ToolResult> {
        var spoke = phraseAlreadySpoken
        val results = mutableMapOf<String, ToolResult>()

        for (call in toolCalls) {
            val callId = call.id ?: continue
            val fn = call.function ?: continue
            val tool = toolRegistry.get(fn.name) ?: continue
            val args = ToolParser.parseToolCallArguments(fn.arguments)

            // Dynamic thinking phrase — spoken aloud and shown in overlay/notification
            if (!spoke) {
                tool.dynamicThinkingPhrase(args)?.takeIf { it.isNotBlank() }?.let { phrase ->
                    spoke = true
                    updateNotification(phrase.take(120))
                    overlayStatus(phrase)
                    speakAsync(phrase)
                }
            }

            // Calls/messages take over the screen and audio — let the spoken
            // announcement finish first so it isn't cut off by the dialer/SMS UI.
            if (tool.waitForSpeech) awaitPhrase()

            toolInProgress = true
            results[callId] = try {
                withTimeoutOrNull(tool.timeoutMs) { tool.execute(args) }
                    ?: ToolResult(false, "", error = "İşlem zaman aşımına uğradı.").also {
                        Log.w(TAG, "tool ${fn.name} timed out")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult(false, "", error = e.message ?: "Araç çalıştırma hatası")
            } finally {
                toolInProgress = false
                lastToolFinishedAt = SystemClock.elapsedRealtime()
            }
            if (tool.successEarcon && results[callId]?.success == true) {
                earcons.play(Earcons.Sound.SUCCESS)
                Haptics.success(this)
            }
        }
        return results
    }

    // ==================================================================
    // ask_user — separate STT for clarification
    // ==================================================================

    private suspend fun askUser(question: String): String? {
        val clean = sanitizeForSpeech(question).ifBlank { question.trim() }
        if (PermissionsHelper.hasOverlay(this)) OverlayService.showMessage(this, clean)
        updateNotification(clean.take(120))
        awaitPhrase() // don't talk over a still-playing status phrase
        speak(clean)

        // Reset the main turn's listening flag so listenForPendingAnswer()
        // can acquire it for the clarification prompt.
        listening.set(false)

        val answer = withTimeoutOrNull(ASK_USER_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                pendingQuestion = cont
                cont.invokeOnCancellation { if (pendingQuestion === cont) pendingQuestion = null }
                pendingListenJob = serviceScope.launch { listenForPendingAnswer() }
            }
        }
        pendingQuestion = null
        return answer
    }

    private suspend fun listenForPendingAnswer() {
        if (!listening.compareAndSet(false, true)) return
        try {
            startListeningFeedback("Cevabını dinliyorum...")
            val text = transcribeWithFallback()
            earcon(open = false)
            if (!text.isNullOrBlank()) overlayUser(text)
            val cont = pendingQuestion
            pendingQuestion = null
            if (cont != null && cont.isActive) {
                cont.resume(value = text, onCancellation = null)
            }
        } finally {
            listening.set(false)
        }
    }

    // ==================================================================
    // Speech
    // ==================================================================

    private suspend fun speak(text: String) {
        if (!secureStore.voiceResponseEnabled || text.isBlank()) return
        val budgetMs = (SPEAK_BASE_MS + text.length * SPEAK_PER_CHAR_MS).coerceAtMost(SPEAK_MAX_MS)
        VoiceActivity.set(VoiceActivity.Phase.SPEAKING)
        coroutineScope {
            val timedOut = AtomicBoolean(false)
            // A TTS engine can silently drop an utterance or stall on the network;
            // stopping playback unblocks both engines so the session moves on.
            val watchdog = launch {
                delay(budgetMs)
                timedOut.set(true)
                Log.w(TAG, "speak watchdog fired after ${budgetMs}ms")
                runCatching { openAiTts.stop() }
                runCatching { androidTts.stop() }
            }
            try {
                val played = secureStore.hasOpenAiKey() && openAiTts.speak(text)
                if (!played && !timedOut.get()) androidTts.speak(text)
            } finally {
                watchdog.cancel()
                if (VoiceActivity.phase.value == VoiceActivity.Phase.SPEAKING) {
                    VoiceActivity.set(if (processing.get()) VoiceActivity.Phase.THINKING else VoiceActivity.Phase.IDLE)
                }
            }
        }
    }

    private fun speakAsync(text: String) {
        phraseJob = serviceScope.launch { speak(text) }
    }

    /** Lets an in-flight status phrase finish before audible/disruptive steps. */
    private suspend fun awaitPhrase() {
        phraseJob?.join()
        phraseJob = null
    }

    private suspend fun showAndSpeak(text: String) {
        val clean = sanitizeForSpeech(text).ifBlank { text }
        overlayAssistant(clean)
        currentChatId?.let { id -> runCatching { chatHistory.append(id, "assistant", clean) } }
        awaitPhrase()
        speak(clean)
    }

    // ==================================================================
    // Session lifecycle
    // ==================================================================

    private fun isEndPhrase(text: String): Boolean {
        val t = text.lowercase(Locale("tr", "TR")).trim().trimEnd('.', '!', ',', '?')
        val farewells = listOf(
            "görüşürüz", "görüşmeyi sonlandır", "görüşmeyi bitir", "görüşmeyi kapat",
            "hoşça kal", "hoşçakal", "teşekkürler görüşürüz"
        )
        // Only on their own: "müziği kapat", "feneri kapat", "yarım saat sonra kapat"
        // and "zamanlayıcıyı iptal et" are commands for the model, not goodbyes
        val bareCommands = listOf("iptal", "iptal et", "boşver", "boş ver", "tamam teşekkürler", "kapat")
        return (farewells + bareCommands).any { t == it } ||
            (t.length <= 25 && farewells.any { t.contains(it) })
    }

    /** @param farewell spoken goodbye; null when the reply was already spoken. */
    private suspend fun finishConversation(farewell: String?) {
        conversation.clear()
        if (farewell != null) {
            val clean = sanitizeForSpeech(farewell).ifBlank { "Görüşürüz!" }
            overlayAssistant(clean)
            currentChatId?.let { id ->
                runCatching { chatHistory.append(id, "assistant", clean) }
            }
            awaitPhrase()
            speak(clean)
        }
        OverlayService.hide(this)
        updateNotification(computeStatusText())
    }

    private fun isThanksOnly(text: String): Boolean {
        val t = text.lowercase(Locale("tr", "TR")).replace(Regex("[^\\p{L} ]"), " ").replace(Regex("\\s+"), " ").trim()
        return t in setOf(
            "teşekkürler", "teşekkür ederim", "çok teşekkürler", "çok teşekkür ederim", "sağ ol", "sağol",
            "çok sağ ol", "sağ olun", "eyvallah", "tamam teşekkürler", "tamam sağ ol", "teşekkürler sağ ol",
            "eline sağlık", "tamam teşekkür ederim"
        )
    }

    private fun endCurrentSession() {
        abandonAudioFocus()
        currentSessionJob?.cancel()
        currentSessionJob = null
        phraseJob?.cancel()
        phraseJob = null
        pendingQuestion?.let { if (it.isActive) it.cancel() }
        pendingQuestion = null
        // The answer listener runs outside the session job; it must not keep the mic
        pendingListenJob?.cancel()
        pendingListenJob = null
        runCatching { openAiTts.stop() }
        runCatching { androidTts.stop() }
        listening.set(false)
        processing.set(false)
        sessionActive = false
        LastPhoto.clear()
        conversation.clear()
        currentChatId = null
        OverlayService.hide(this)
        OverlayAnchor.hide()
        updateNotification(computeStatusText())
    }

    // ==================================================================
    // Output / UI
    // ==================================================================

    private fun overlayUser(text: String) {
        if (PermissionsHelper.hasOverlay(this)) OverlayService.showUserMessage(this, text)
    }

    private fun overlayUserPartial(text: String) {
        if (PermissionsHelper.hasOverlay(this)) OverlayService.showUserPartial(this, text)
    }

    private fun overlayAssistant(text: String) {
        if (PermissionsHelper.hasOverlay(this)) OverlayService.showMessage(this, text)
    }

    private fun overlayStatus(text: String) {
        if (PermissionsHelper.hasOverlay(this)) OverlayService.showStatus(this, text)
    }

    private fun sanitizeForSpeech(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_~`#>]+"), "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    private suspend fun handleError(message: String) {
        hadError = true
        earcons.play(Earcons.Sound.ERROR)
        Haptics.error(this)
        val detail = "Bir sorun oluştu: $message"
        if (PermissionsHelper.hasOverlay(this)) {
            OverlayService.showMessage(this, detail)
        } else {
            updateNotification(detail.take(240))
        }
        speakAsync("Üzgünüm, bir sorun oluştu.")
    }

    // ==================================================================
    // Audio focus — pause music/podcasts during the session
    // ==================================================================

    private fun requestAudioFocus() {
        if (audioFocusGranted) return
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // Permanent loss (e.g. incoming call) — end the session. While the
                        // screen agent works, the loss is an app it opened starting audio.
                        if (!ScreenAgent.running) endCurrentSession()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // Temporary loss (notification sound, navigation prompt)
                        runCatching { openAiTts.stop() }
                        runCatching { androidTts.stop() }
                    }
                    else -> Unit
                }
            }
            val result = am.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocusListener = listener // only save on success — no leak
                audioFocusGranted = true
            }
        }
    }

    private fun abandonAudioFocus() {
        val wasGranted = audioFocusGranted
        audioFocusGranted = false
        val listener = audioFocusListener
        audioFocusListener = null
        if (!wasGranted || listener == null) return
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocus(listener)
        }
    }

    // ==================================================================
    // Listening feedback
    // ==================================================================

    private fun startListeningFeedback(text: String) {
        updateNotification(text.take(120))
        if (PermissionsHelper.hasOverlay(this)) OverlayService.showListening(this, text)
        VoiceActivity.set(VoiceActivity.Phase.LISTENING)
        Haptics.listenStart(this)
        earcon(open = true)
    }

    private fun earcon(open: Boolean) {
        earcons.play(if (open) Earcons.Sound.LISTEN_START else Earcons.Sound.LISTEN_END)
    }

    // ==================================================================
    // Notification
    // ==================================================================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "deepAssist Asistan",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "deepAssist arka plan asistan servisi"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun notifyStartupStatus() {
        runCatching { MessageListenerService.rebindIfNeeded(this) }
        startForegroundCompat(buildNotification(computeStatusText()))
    }

    private fun computeStatusText(): String {
        val missing = mutableListOf<String>()
        if (!secureStore.hasOpenAiKey()) missing.add("OpenAI anahtarı (derleme)")
        if (!secureStore.hasDeepSeekKey()) missing.add("DeepSeek anahtarı (derleme)")
        if (!PermissionsHelper.hasMicrophone(this)) missing.add("mikrofon izni")
        if (!PermissionsHelper.hasOverlay(this)) missing.add("ekran üstü izni")
        if (!PermissionsHelper.isAccessibilityEnabled(this)) {
            missing.add("erişilebilirlik servisi")
        } else if (AccessibilitySvc.isStalled(this)) {
            missing.add("erişilebilirlik servisi durdu, ayarlardan kapatıp açın")
        }
        if (!PermissionsHelper.isNotificationListenerEnabled(this)) missing.add("bildirim erişimi")
        return if (missing.isEmpty()) "Dinlemede..." else "Eksik: ${missing.joinToString(", ")}"
    }

    /**
     * Never throws. A failed startForeground used to crash onCreate, and every crash
     * also knocks out the accessibility service (power gesture) until it is toggled.
     * Android 14+ uses specialUse: dataSync is capped at 6 h/day on Android 15, and the
     * always-on service crashed daily when the cap ran out. The microphone type is
     * tried first and dropped when Android refuses it from the background.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT < 29) {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { DiagLog.event("Service", "startForeground failed ${DiagLog.describe(it)}") }
            return
        }
        val base = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        val attempts = if (Build.VERSION.SDK_INT >= 30 && PermissionsHelper.hasMicrophone(this)) {
            listOf(base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, base)
        } else {
            listOf(base)
        }
        for (type in attempts) {
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                if (type != lastFgsType) {
                    lastFgsType = type
                    DiagLog.event("Service", "foreground type=0x${Integer.toHexString(type)}")
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground type=0x${Integer.toHexString(type)} failed", e)
                DiagLog.event("Service", "startForeground 0x${Integer.toHexString(type)} failed ${DiagLog.describe(e)}")
            }
        }
    }

    /** Safety net: only time-limited types (dataSync on Android 15) get here. Stop instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagLog.event("Service", "onTimeout type=0x${Integer.toHexString(fgsType)}")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * The power gesture lives in the accessibility service. When it is off or has
     * stalled (Android doesn't rebind a crashed one) only the long-press still works,
     * so tell the user once in a while — the helper has to toggle it in Settings.
     */
    /** Gives a just-launched panel a moment to come up; never waits long. */
    private suspend fun awaitPanel() {
        val deadline = SystemClock.elapsedRealtime() + PANEL_WAIT_MS
        while (!SessionActivity.isShowing() && SystemClock.elapsedRealtime() < deadline) {
            delay(50)
        }
    }

    private suspend fun warnIfPowerTriggerBroken() {
        val broken = !PermissionsHelper.isAccessibilityEnabled(this) || AccessibilitySvc.isStalled(this)
        if (!broken) return
        val prefs = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_A11Y_WARNED_AT, 0L) < A11Y_WARN_INTERVAL_MS) return
        prefs.edit().putLong(KEY_A11Y_WARNED_AT, now).apply()
        DiagLog.event("Session", "spoke accessibility warning")
        speak(
            "Bilgin olsun, güç tuşuyla açma şu an çalışmıyor. Bir yakının telefonun " +
                "erişilebilirlik ayarlarında deepAssist'i kapatıp tekrar açmalı."
        )
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getForegroundService(
            this, 0,
            Intent(this, AssistantForegroundService::class.java)
                .apply { action = ACTION_TAP_TRIGGER },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val settingsIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("deepAssist")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(null, "Ayarlar", settingsIntent).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    // ==================================================================
    // Wake lock
    // ==================================================================

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deepassist:trigger").apply {
            setReferenceCounted(false)
            acquire(180_000L)
        }
    }.getOrNull()

    // ==================================================================
    // Lifecycle
    // ==================================================================

    override fun onDestroy() {
        DiagLog.event("Service", "onDestroy")
        AccessibilitySvc.onPowerKeyDetected = null
        AccessibilitySvc.onStateChanged = null
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        pendingQuestion?.let { if (it.isActive) it.cancel() }
        pendingQuestion = null
        runCatching { openAiTts.stop() }
        runCatching { androidTts.stop() }
        runCatching { androidTts.shutdown() }
        earcons.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "assistant"
        const val NOTIFICATION_ID = 1001

        const val ACTION_VOICE_TRIGGER = "com.deepassist.VOICE_TRIGGER"
        const val ACTION_POWER_TRIGGER = "com.deepassist.POWER_TRIGGER"
        const val ACTION_TAP_TRIGGER = "com.deepassist.TAP_TRIGGER"
        const val ACTION_END_SESSION = "com.deepassist.END_SESSION"
        const val ACTION_STOP = "com.deepassist.STOP"
        const val ACTION_DEBUG_PROMPT = "com.deepassist.DEBUG_PROMPT"
        const val EXTRA_DEBUG_TEXT = "text"
        const val EXTRA_RESUME_CHAT_ID = "resume_chat_id"

        private const val TAG = "AssistantFgs"

        /** Leave hints this soon after a tool ran are that tool opening an app. */
        private const val TOOL_HANDOFF_WINDOW_MS = 3_000L

        @Volatile
        var toolInProgress = false

        @Volatile
        private var lastToolFinishedAt = 0L

        /** True from a trigger until that session ends — the panel's launch fallback checks it. */
        @Volatile
        var sessionActive = false

        private const val STATE_PREFS = "deepassist_state"
        private const val KEY_A11Y_WARNED_AT = "a11y_warned_at"
        private const val A11Y_WARN_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val PANEL_WAIT_MS = 600L

        /** Framing cues use the fast on-device voice; quieter so they don't startle. */
        private const val CUE_VOLUME = 0.6f

        /** Set by SessionActivity when an app opened by a tool took over the screen. */
        @Volatile
        var uiHandedOff = false

        /** True while a tool runs or just finished — its activity launch isn't the user leaving. */
        fun isToolHandoff(): Boolean =
            toolInProgress || SystemClock.elapsedRealtime() - lastToolFinishedAt < TOOL_HANDOFF_WINDOW_MS

        fun resumeChat(context: Context, chatId: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, AssistantForegroundService::class.java)
                        .apply {
                            action = ACTION_VOICE_TRIGGER
                            putExtra(EXTRA_RESUME_CHAT_ID, chatId)
                        }
                )
            }
        }

        private const val MAX_TOOL_ROUNDS = 6
        private const val MAX_CONVERSATION_TURNS = 50
        private const val ASK_USER_TIMEOUT_MS = 30_000L
        private const val MAX_RECORD_MS = 8_000L
        private const val SILENCE_STOP_MS = 3_000L
        private const val SILENCE_THRESHOLD = 500
        private const val PREVIOUS_SESSION_JOIN_MS = 2_000L
        private const val SPEAK_BASE_MS = 8_000L
        private const val SPEAK_PER_CHAR_MS = 90L
        private const val SPEAK_MAX_MS = 300_000L

        /** Timestamp (elapsedRealtime) of the last screen-off-initiated session end.
         *  Used by AccessibilitySvc to avoid immediately restarting a session after
         *  the user pressed power to end it. */
        @Volatile
        var lastScreenOffEndAt: Long = 0L

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, AssistantForegroundService::class.java))
            }
        }

        /** @return false when Android refused to start the service (e.g. from the background). */
        fun trigger(context: Context, action: String): Boolean =
            runCatching {
                context.startForegroundService(
                    Intent(context, AssistantForegroundService::class.java)
                        .apply { this.action = action }
                )
            }.onFailure {
                Log.w(TAG, "trigger $action failed", it)
                DiagLog.event("Trigger", "${action.substringAfterLast('.')} start failed ${DiagLog.describe(it)}")
            }.isSuccess

        fun endSession(context: Context) {
            trigger(context, ACTION_END_SESSION)
        }
    }
}
