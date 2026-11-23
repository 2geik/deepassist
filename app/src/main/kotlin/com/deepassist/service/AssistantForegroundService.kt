package com.deepassist.service

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
import android.media.AudioManager
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
import com.deepassist.speech.AndroidStt
import com.deepassist.speech.MetaStt
import com.deepassist.speech.RealtimeTts
import com.deepassist.speech.SttResult
import com.deepassist.agent.ScreenAgent
import com.deepassist.camera.LastPhoto
import com.deepassist.tools.EndConversationTool
import com.deepassist.tools.ScreenUnlock
import com.deepassist.tools.Tool
import com.deepassist.tools.ToolRegistry
import com.deepassist.util.DiagLog
import com.deepassist.util.Net
import com.deepassist.util.Haptics
import com.deepassist.util.PermissionsHelper
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Central orchestrator — cascaded STT (Meta realtime) → DeepSeek LLM → TTS (OpenAI realtime).
 *
 * Flow (per turn):
 *   transcribe() → DeepSeek streamChat() → (tool calls? execute,
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
    // TTS: OpenAI gpt-realtime-2.1-mini (one session per assistant session), Android TTS as fallback
    private lateinit var realtimeTts: RealtimeTts
    private lateinit var androidTts: AndroidTts
    private lateinit var earcons: Earcons

    // STT: Meta Muse Voice Transcribe (realtime), Android's SpeechRecognizer as fallback
    private lateinit var metaStt: MetaStt
    private lateinit var androidStt: AndroidStt

    // A fallback, once taken, holds for the rest of the session so the voice and the
    // recognizer don't switch back and forth; across sessions for FALLBACK_HOLD_MS
    @Volatile private var sttOnDevice = false
    @Volatile private var ttsOnDevice = false
    @Volatile private var metaDownUntil = 0L
    @Volatile private var cloudTtsDownUntil = 0L

    /** The "listening ended" chime of the current listen already played. */
    @Volatile private var listenEndPlayed = false

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
    @Volatile private var ttsCloseJob: Job? = null
    @Volatile private var waitingJob: Job? = null
    @Volatile private var waitingPaused = false
    private var screenOffReceiver: BroadcastReceiver? = null
    @Volatile private var hadError = false

    // Bumped per trigger so a cancelled session's cleanup can't clobber its successor
    private val sessionGeneration = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.event("Service", "onCreate")
        // The phone's DNS can take 30 s: resolve the API hosts before they are needed
        Net.prefetch(*Net.API_HOSTS)
        createNotificationChannel()
        secureStore = SecureStore(this)
        notifyStartupStatus()

        conversation = ConversationManager()
        deepSeek = DeepSeekClient { secureStore.deepseekApiKey }
        memoryExtractor = MemoryExtractor(this, deepSeek)
        realtimeTts = RealtimeTts { secureStore.openAiApiKey }
        androidTts = AndroidTts(this)
        earcons = Earcons(this)
        metaStt = MetaStt { secureStore.metaApiKey }
        androidStt = AndroidStt(this)
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
                sttOnDevice = false
                ttsOnDevice = false
                Net.prefetch(*Net.API_HOSTS)
                // Connecting the voice takes ~1.5 s; do it while the user talks
                ttsCloseJob?.cancel()
                if (cloudTtsUsable()) realtimeTts.warmUp()
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
                var askedToRepeat = false
                while (isActive && turn < MAX_CONVERSATION_TURNS && !sessionEnded) {
                    turn++
                    runCatching { wakeLock?.acquire(180_000L) }
                    val text = if (turn == 1 && !debugPrompt.isNullOrBlank()) {
                        debugPrompt
                    } else {
                        startListeningFeedback("Dinliyorum...")
                        transcribe { partial -> overlayUserPartial(partial) }.also { listenEnded(it) }
                    }

                    if (text.isNullOrBlank()) {
                        stopWaitingTone()
                        if (turn == 1) {
                            // Without the DeepSeek key there is no answer to give
                            if (secureStore.hasDeepSeekKey() && !askedToRepeat) {
                                // "Tekrar dener misin?" must really listen again: once
                                askedToRepeat = true
                                val again = "Seni anlayamadım, tekrar dener misin?"
                                overlayAssistant(again)
                                speak(again)
                                turn-- // still the first request
                                continue
                            }
                            val hint = if (!secureStore.hasDeepSeekKey()) {
                                "Bu sürüm DeepSeek anahtarı olmadan derlenmiş, cevap veremiyorum."
                            } else {
                                "Seni yine duyamadım, birazdan tekrar dene."
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
                    // Keep the voice session for a quick next request; close it when idle
                    ttsCloseJob?.cancel()
                    ttsCloseJob = serviceScope.launch {
                        delay(TTS_IDLE_CLOSE_MS)
                        realtimeTts.close()
                    }
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

    /**
     * One listening turn, for requests and for ask_user answers: Meta's realtime
     * recognizer, or Android's SpeechRecognizer when Meta is unavailable. After a Meta
     * failure the session stays on Android (no switching back and forth), and the
     * next sessions skip Meta for [FALLBACK_HOLD_MS].
     *
     * @param onPartial live transcript for the panel; null for ask_user answers.
     */
    private suspend fun transcribe(onPartial: ((String) -> Unit)?): String? {
        if (!PermissionsHelper.hasMicrophone(this)) return null
        val partial: (String) -> Unit = { text -> onPartial?.invoke(text) }

        if (metaUsable()) {
            when (val result = metaStt.listen(partial) { speechEnded() }) {
                is SttResult.Final -> return result.text
                is SttResult.NoSpeech -> return null
                is SttResult.Unavailable -> {
                    sttOnDevice = true
                    metaDownUntil = SystemClock.elapsedRealtime() + FALLBACK_HOLD_MS
                    DiagLog.event("Speech", "STT fallback android")
                    // Android's recognizer plays its own start sound: that asks for the words again
                }
            }
        }
        if (!androidStt.isAvailable()) return null
        // Google's recognizer chimes itself; ours on top made a double sound
        listenEndPlayed = true
        return when (val result = androidStt.listen(partial) { speechEnded() }) {
            is SttResult.Final -> result.text
            else -> null
        }
    }

    private fun metaUsable(): Boolean =
        secureStore.hasMetaKey() && !sttOnDevice &&
            SystemClock.elapsedRealtime() >= metaDownUntil &&
            !(BuildConfig.DEBUG && debugSttMode == SPEECH_MODE_ANDROID)

    private fun cloudTtsUsable(): Boolean =
        secureStore.hasOpenAiKey() && !ttsOnDevice &&
            SystemClock.elapsedRealtime() >= cloudTtsDownUntil &&
            !(BuildConfig.DEBUG && debugTtsMode == SPEECH_MODE_ANDROID)

    /**
     * The microphone stopped because the user is done: the "listening ended" chime,
     * and the waiting sound runs until the answer is spoken.
     */
    private fun speechEnded() {
        if (!listenEndPlayed) {
            listenEndPlayed = true
            earcon(open = false)
        }
        VoiceActivity.set(VoiceActivity.Phase.THINKING)
        startWaitingTone()
    }

    /** After a listen: the end chime if the recognizer didn't signal the end itself. */
    private fun listenEnded(text: String?) {
        if (!listenEndPlayed) {
            listenEndPlayed = true
            earcon(open = false)
        }
        if (text.isNullOrBlank()) stopWaitingTone()
    }

    // ==================================================================
    // LLM pipeline
    // ==================================================================

    /** @return true when the conversation ended (farewell or error) — stop the listen loop. */
    private suspend fun processUserPrompt(text: String): Boolean {
        processing.set(true)
        VoiceActivity.set(VoiceActivity.Phase.THINKING)
        startWaitingTone()
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
            stopWaitingTone()
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

            // An app opened on the lock screen stays hidden behind it: unlock first,
            // or tell the user to (PIN/pattern) and skip the call if it stays locked
            if (tool.needsUnlockedScreen(args)) {
                waitingPaused = true
                val blocked = try {
                    ScreenUnlock.ensure(this)
                } finally {
                    waitingPaused = false
                }
                if (blocked != null) {
                    results[callId] = ToolResult(false, "", error = blocked, speakDirectly = true)
                    continue
                }
            }

            // Calls/messages take over the screen and audio — let the spoken
            // announcement finish first so it isn't cut off by the dialer/SMS UI.
            if (tool.waitForSpeech) awaitPhrase()

            // Lookups keep the waiting sound; tools that speak, guide or open an app don't
            waitingPaused = !tool.waitingTone
            toolInProgress = true
            val toolStart = SystemClock.elapsedRealtime()
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
                waitingPaused = false
                lastToolFinishedAt = SystemClock.elapsedRealtime()
                val took = lastToolFinishedAt - toolStart
                if (took >= 1_000L) DiagLog.event("Tool", "${fn.name} $took ms")
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
        stopWaitingTone()
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
        // The request goes on (tool, model): waiting again until the answer is spoken
        if (processing.get()) {
            VoiceActivity.set(VoiceActivity.Phase.THINKING)
            startWaitingTone()
        }
        return answer
    }

    private suspend fun listenForPendingAnswer() {
        if (!listening.compareAndSet(false, true)) return
        try {
            startListeningFeedback("Cevabını dinliyorum...")
            val text = transcribe(onPartial = null)
            listenEnded(text)
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

    /**
     * Speaks [text] with the realtime voice, or with Android's voice when the realtime
     * one can't start within 3 s. After such a failure the session stays on Android's
     * voice so the two don't alternate.
     */
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
                runCatching { realtimeTts.stop() }
                runCatching { androidTts.stop() }
            }
            try {
                val outcome = if (cloudTtsUsable()) realtimeTts.speak(text) else RealtimeTts.Outcome.FAILED
                if (outcome == RealtimeTts.Outcome.FAILED && !timedOut.get()) {
                    if (!ttsOnDevice && cloudTtsUsable()) {
                        ttsOnDevice = true
                        cloudTtsDownUntil = SystemClock.elapsedRealtime() + FALLBACK_HOLD_MS
                        DiagLog.event("Speech", "TTS fallback android")
                    }
                    androidTts.speak(text)
                }
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
        stopWaitingTone()
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
        stopWaitingTone()
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
        stopWaitingTone()
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
        runCatching { realtimeTts.stop() }
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
        stopWaitingTone()
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
                        runCatching { realtimeTts.stop() }
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
        listenEndPlayed = false
        updateNotification(text.take(120))
        if (PermissionsHelper.hasOverlay(this)) OverlayService.showListening(this, text)
        VoiceActivity.set(VoiceActivity.Phase.LISTENING)
        Haptics.listenStart(this)
        // Android's recognizer (the fallback) plays its own start sound
        if (metaUsable()) earcon(open = true)
    }

    private fun earcon(open: Boolean) {
        earcons.play(if (open) Earcons.Sound.LISTEN_START else Earcons.Sound.LISTEN_END)
    }

    /**
     * A soft note every [WAIT_TONE_INTERVAL_MS] from the end of the request until the
     * answer is spoken, so the user knows the assistant is still working. Silent while
     * anything speaks (status phrase, tool speech) and while a tool other than a
     * lookup runs ([Tool.waitingTone]).
     */
    private fun startWaitingTone() {
        if (waitingJob?.isActive == true) return
        waitingPaused = false
        waitingJob = serviceScope.launch {
            delay(WAIT_TONE_FIRST_MS) // after the "listening ended" chime
            while (isActive) {
                if (!waitingPaused && VoiceActivity.phase.value == VoiceActivity.Phase.THINKING) {
                    earcons.play(Earcons.Sound.WAIT, WAIT_TONE_VOLUME)
                }
                delay(WAIT_TONE_INTERVAL_MS)
            }
        }
    }

    private fun stopWaitingTone() {
        waitingJob?.cancel()
        waitingJob = null
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
        runCatching { realtimeTts.stop() }
        runCatching { realtimeTts.close() }
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

        const val SPEECH_MODE_AUTO = "auto"
        const val SPEECH_MODE_ANDROID = "android"

        /** Debug builds: "android" uses Android's recognizer / voice even when the cloud works. */
        @Volatile
        var debugSttMode = SPEECH_MODE_AUTO

        @Volatile
        var debugTtsMode = SPEECH_MODE_AUTO

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
        /** After a cloud STT/TTS failure, the next sessions start on Android's for this long. */
        private const val FALLBACK_HOLD_MS = 2 * 60 * 1000L

        /** The realtime voice session stays open this long after a session ends. */
        private const val TTS_IDLE_CLOSE_MS = 3 * 60 * 1000L
        private const val PREVIOUS_SESSION_JOIN_MS = 2_000L
        private const val WAIT_TONE_FIRST_MS = 700L
        private const val WAIT_TONE_INTERVAL_MS = 2_500L
        private const val WAIT_TONE_VOLUME = 0.5f
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
