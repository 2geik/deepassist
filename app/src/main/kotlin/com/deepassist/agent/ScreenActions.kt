package com.deepassist.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The agent's hands. Accessibility actions first (they hit the right view even
 * when something overlaps it); a gesture at the element's centre otherwise.
 * Every function reports what really happened — the agent never assumes.
 */
class ScreenActions(private val service: AccessibilityService) {

    suspend fun tap(element: ScreenReader.Element): Boolean {
        val node = element.node.also { runCatching { it.refresh() } }
        clickableSelfOrAncestor(node)?.let { target ->
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val b = element.bounds
        return gesture(Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }, durationMs = 80)
    }

    /** Types [text] into a field and reads it back; false when the field didn't take it. */
    suspend fun type(element: ScreenReader.Element, text: String): Boolean {
        val node = element.node.also { runCatching { it.refresh() } }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) && readBack(node, text)) return true

        // Web views and some custom fields ignore SET_TEXT: paste instead, then
        // clear the clipboard so the message doesn't linger there
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
        return withContext(Dispatchers.Main) {
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("deepAssist", text)) }
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (Build.VERSION.SDK_INT >= 28) runCatching { clipboard.clearPrimaryClip() }
            pasted && readBack(node, text)
        }
    }

    /** The keyboard's enter / search key in the focused field (Android 11+). */
    fun submit(element: ScreenReader.Element): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val node = element.node.also { runCatching { it.refresh() } }
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Scrolls [element] (or the first scrollable list) by a page; a swipe as the last resort. */
    suspend fun scroll(screen: ScreenReader.Screen, element: ScreenReader.Element?, down: Boolean): Boolean {
        val target = element?.takeIf { it.scrollable } ?: screen.elements.firstOrNull { it.scrollable }
        if (target != null) {
            runCatching { target.node.refresh() }
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.node.performAction(action)) return true
        }
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (from, to) = if (down) 0.72f to 0.30f else 0.30f to 0.72f
        return gesture(
            Path().apply {
                moveTo(x, metrics.heightPixels * from)
                lineTo(x, metrics.heightPixels * to)
            },
            durationMs = 350
        )
    }

    fun back() = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun home() = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    fun notifications() = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun readBack(node: AccessibilityNodeInfo, expected: String): Boolean {
        runCatching { node.refresh() }
        val now = node.text?.toString().orEmpty()
        return now.contains(expected.trim()) || expected.trim().contains(now.trim()) && now.isNotBlank()
    }

    /** dispatchGesture with a timeout — its callback is not guaranteed to arrive. */
    private suspend fun gesture(path: Path, durationMs: Long): Boolean = withTimeoutOrNull(durationMs + 1_500L) {
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val ok = service.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!ok && cont.isActive) cont.resume(false)
        }
    } ?: false
}
