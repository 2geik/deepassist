package com.deepassist.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.deepassist.SessionActivity
import com.deepassist.util.DiagLog

/**
 * Default-assistant entry point (home button long-press / assist gesture).
 * Do NOT override onShowSession — removed in newer SDKs; the session below
 * receives the show callback instead.
 */
class DeepAssistVoiceInteraction : VoiceInteractionService() {

    override fun onLaunchVoiceAssistFromKeyguard() {
        DiagLog.event("Trigger", "assist from keyguard")
        if (!AssistantForegroundService.trigger(this, AssistantForegroundService.ACTION_VOICE_TRIGGER)) {
            SessionActivity.bootstrap(this, AssistantForegroundService.ACTION_VOICE_TRIGGER)
        }
    }
}

class DeepAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DeepAssistSession(this)
}

/** Immediately hands off to the foreground service and dismisses itself. */
class DeepAssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i("DeepAssistSession", "assist gesture → trigger")
        DiagLog.event("Trigger", "assist gesture")
        if (!AssistantForegroundService.trigger(context, AssistantForegroundService.ACTION_VOICE_TRIGGER)) {
            // Service start refused: open the panel through the assist session, which
            // may always start its own activity; the panel then starts the service
            runCatching { startAssistantActivity(SessionActivity.bootstrapIntent(context, AssistantForegroundService.ACTION_VOICE_TRIGGER)) }
                .onFailure { SessionActivity.bootstrap(context, AssistantForegroundService.ACTION_VOICE_TRIGGER) }
        }
        // finish(), not hide(): a merely hidden session lingers and may not be shown again
        finish()
    }
}

/**
 * Stub recognizer: voice_interaction.xml requires android:recognitionService,
 * but all real speech recognition goes through Whisper in the orchestrator.
 */
class DeepAssistRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) {
        // nothing to cancel
    }

    override fun onStopListening(listener: Callback?) {
        // nothing to stop
    }
}
