package com.deepassist.service

import android.content.Context
import com.deepassist.SessionActivity

/**
 * Facade for the session chat UI. Mutates [ChatSession] and makes sure
 * [SessionActivity] is visible. (The old TYPE_APPLICATION_OVERLAY window was
 * replaced by the activity because Android hides overlay windows on the lock
 * screen; the activity shows over the keyguard and keeps the screen on.)
 */
object OverlayService {

    /** Clears previous bubbles and shows the opening listening pill. */
    fun newSession(context: Context, statusText: String) {
        ChatSession.newSession(statusText)
        SessionActivity.ensureVisible(context)
    }

    fun showUserMessage(context: Context, text: String) {
        if (text.isBlank()) return
        ChatSession.addUser(text)
        SessionActivity.ensureVisible(context)
    }

    /** Live transcription: updates the current user bubble in place. */
    fun showUserPartial(context: Context, text: String) {
        if (text.isBlank()) return
        ChatSession.updatePartial(text)
        SessionActivity.ensureVisible(context)
    }

    /** Assistant reply bubble (name kept for existing call sites). */
    fun showMessage(context: Context, text: String) {
        if (text.isBlank()) return
        ChatSession.addAssistant(text)
        SessionActivity.ensureVisible(context)
    }

    fun showStatus(context: Context, text: String) {
        if (text.isBlank()) return
        ChatSession.addStatus(text)
        SessionActivity.ensureVisible(context)
    }

    /** Pulsing "microphone open" pill. */
    fun showListening(context: Context, text: String) {
        ChatSession.addListening(text.ifBlank { "Dinliyorum..." })
        SessionActivity.ensureVisible(context)
    }

    fun hide(@Suppress("UNUSED_PARAMETER") context: Context) {
        SessionActivity.close()
    }

    /** Pre-seeds the overlay UI with messages from a resumed conversation. */
    fun seedHistory(context: Context, messages: List<Pair<Boolean, String>>) {
        ChatSession.seedHistory(messages)
        SessionActivity.ensureVisible(context)
    }
}
