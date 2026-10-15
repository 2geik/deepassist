package com.deepassist.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Turns the app on screen into a short numbered list the model can act on:
 *
 *   Uygulama: WhatsApp, klavye açık
 *   [3] Yazı alanı "Mesaj" değer="" (odakta)
 *   [4] Buton "Gönder"
 *
 * Text inside a tappable row is merged into that row, so each line is one
 * thing the user would perceive. Our own panel and the system bars are skipped.
 */
object ScreenReader {

    private const val MAX_ELEMENTS = 160
    private const val MAX_CHARS = 7_000
    private const val MAX_NODES = 2_000

    class Element(
        val id: Int,
        val role: String,
        val label: String,
        val value: String?,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val selected: Boolean,
        val focused: Boolean,
        val password: Boolean,
        val bounds: Rect,
        val viewId: String?,
        val node: AccessibilityNodeInfo
    ) {
        val actionable: Boolean get() = clickable || editable || scrollable || checkable

        fun line(): String = buildString {
            append("[").append(id).append("] ").append(role)
            if (label.isNotBlank()) append(" \"").append(label).append('"')
            if (editable) append(" değer=\"").append(if (password) "••••" else value.orEmpty().take(80)).append('"')
            val flags = buildList {
                if (checkable) add(if (checked) "açık" else "kapalı")
                if (selected) add("seçili")
                if (focused && editable) add("odakta")
                if (password) add("şifre alanı")
                if (scrollable) add("kaydırılabilir")
                if (clickable && !editable && role != "Buton") add("dokunulabilir")
            }
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString(", ")).append(")")
        }
    }

    class Screen(
        val packageName: String,
        val appName: String,
        val elements: List<Element>,
        val truncated: Boolean,
        val keyboardOpen: Boolean,
        val webContent: Boolean
    ) {
        /** Too little structure to act on: a screenshot is needed. */
        val poor: Boolean
            get() = elements.count { it.label.isNotBlank() } < 3 || (webContent && elements.count { it.actionable } < 4)

        fun find(id: Int): Element? = elements.firstOrNull { it.id == id }

        fun describe(): String = buildString {
            append("Uygulama: ").append(appName)
            if (keyboardOpen) append(", klavye açık")
            append('\n')
            for (e in elements) append(e.line()).append('\n')
            if (truncated) append("(Ekranda daha fazla öğe var; kaydırınca görünür.)\n")
        }.take(MAX_CHARS)

        /** Changes whenever what the user would see changes — for loop detection. */
        fun signature(): String =
            packageName + "|" + elements.take(60).joinToString("|") { "${it.role}:${it.label}:${it.value}:${it.checked}" }.hashCode()
    }

    fun read(service: AccessibilityService): Screen? {
        val own = service.packageName
        val screenHeight = service.resources.displayMetrics.heightPixels
        var keyboard = false
        var best: AccessibilityWindowInfo? = null
        for (window in runCatching { service.windows }.getOrDefault(emptyList())) {
            when (window.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                    keyboard = true
                    continue
                }
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> continue
            }
            val root = window.root ?: continue
            if (root.packageName?.toString() == own) continue
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            // Status and navigation bars are system windows too; the shade and dialogs are big
            if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM && bounds.height() < screenHeight * 0.3) continue
            if (best == null || window.layer > best.layer) best = window
        }
        val root = best?.root ?: service.rootInActiveWindow?.takeIf { it.packageName?.toString() != own } ?: return null
        val pkg = root.packageName?.toString().orEmpty()

        val builder = Builder()
        builder.visit(root, depth = 0, mergedIntoParent = false)
        return Screen(
            packageName = pkg,
            appName = appLabel(service, pkg),
            elements = builder.elements,
            truncated = builder.truncated,
            keyboardOpen = keyboard,
            webContent = builder.web
        )
    }

    private class Builder {
        val elements = mutableListOf<Element>()
        var truncated = false
        var web = false
        private var visited = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int, mergedIntoParent: Boolean) {
            if (depth > 45 || ++visited > MAX_NODES) {
                truncated = true
                return
            }
            if (!node.isVisibleToUser) return
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("WebView")) web = true
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = node.hintText?.toString()?.trim().orEmpty()
            val actionable = node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.isCheckable

            var childrenMerged = mergedIntoParent
            if (actionable) {
                var label = when {
                    node.isEditable -> listOf(desc, hint).firstOrNull { it.isNotBlank() }.orEmpty()
                    else -> listOf(desc, text).filter { it.isNotBlank() }.distinct().joinToString(" – ")
                }
                if (label.isBlank() && !node.isEditable && !node.isScrollable) {
                    label = childText(node, limit = 4)
                    childrenMerged = label.isNotBlank()
                }
                add(node, cls, label.take(90), value = if (node.isEditable) text else null)
            } else if (!mergedIntoParent && (text.isNotBlank() || desc.isNotBlank())) {
                val role = if (cls.contains("Image")) "Görsel" else "Metin"
                add(node, cls, (if (text.isNotBlank()) text else desc).take(220), value = null, forcedRole = role)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1, childrenMerged)
            }
        }

        private fun add(node: AccessibilityNodeInfo, cls: String, label: String, value: String?, forcedRole: String? = null) {
            if (elements.size >= MAX_ELEMENTS) {
                truncated = true
                return
            }
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            elements += Element(
                id = elements.size + 1,
                role = forcedRole ?: roleOf(node, cls),
                label = label,
                value = value,
                clickable = node.isClickable || node.isLongClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                selected = node.isSelected,
                focused = node.isFocused,
                password = node.isPassword,
                bounds = bounds,
                viewId = node.viewIdResourceName,
                node = node
            )
        }

        private fun childText(node: AccessibilityNodeInfo, limit: Int): String {
            val parts = mutableListOf<String>()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (parts.size >= limit || depth > 6) return
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    if (!c.isVisibleToUser) continue
                    val t = (c.text?.toString() ?: c.contentDescription?.toString())?.trim().orEmpty()
                    if (t.isNotBlank() && t !in parts) parts += t
                    walk(c, depth + 1)
                }
            }
            walk(node, 0)
            return parts.joinToString(" – ")
        }

        private fun roleOf(node: AccessibilityNodeInfo, cls: String): String = when {
            node.isEditable || cls.contains("EditText") -> "Yazı alanı"
            cls.contains("Switch") || cls.contains("Toggle") -> "Anahtar"
            cls.contains("CheckBox") -> "Onay kutusu"
            cls.contains("RadioButton") -> "Seçenek"
            cls.contains("Tab") -> "Sekme"
            node.isScrollable -> "Liste"
            cls.contains("Button") -> "Buton"
            cls.contains("Image") && node.isClickable -> "Buton"
            else -> "Öğe"
        }
    }

    private fun appLabel(service: AccessibilityService, pkg: String): String = runCatching {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
