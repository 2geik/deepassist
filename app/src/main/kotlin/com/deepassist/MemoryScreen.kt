package com.deepassist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.deepassist.agent.ShortcutStore
import com.deepassist.data.MemoryEntry
import com.deepassist.data.MemoryStore
import com.deepassist.ui.EmptyState
import com.deepassist.ui.ScreenHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the assistant knows about the user, grouped by the extractor's [Kategori] prefix. */
@Composable
fun MemoryScreen() {
    val context = LocalContext.current
    val store = remember { MemoryStore.get(context) }
    var entries by remember { mutableStateOf(store.list()) }
    var filter by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val shortcutStore = remember { ShortcutStore.get(context) }
    var shortcuts by remember { mutableStateOf(shortcutStore.list()) }

    val categories = remember(entries) { entries.mapNotNull { categoryOf(it.text) }.distinct().sorted() }
    val shown = entries
        .filter { filter == null || categoryOf(it.text) == filter }
        .sortedByDescending { maxOf(it.createdAt, it.updatedAt) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Hafıza",
            subtitle = "Asistanın senin hakkında bildikleri",
            actions = {
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Tümünü sil", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )

        if (categories.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Hepsi") })
                categories.forEach { category ->
                    FilterChip(
                        selected = filter == category,
                        onClick = { filter = if (filter == category) null else category },
                        label = { Text(category) }
                    )
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        title = "Henüz bir şey yok",
                        body = "Asistan konuşmalardan öğrendikçe yakınların, ilgi alanların ve tercihlerin burada görünür."
                    )
                }
            }
            items(shown, key = { it.id }) { entry ->
                MemoryRow(entry = entry, onDelete = {
                    store.remove(entry.id)
                    entries = store.list()
                })
                HorizontalDivider(Modifier.padding(start = 24.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (shortcuts.isNotEmpty()) {
                item(key = "shortcuts-header") {
                    Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)) {
                        Text("Öğrenilen kısayollar", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Telefonda yaptığı işlerin yolunu hatırlar, bir dahakine daha hızlı yapar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(shortcuts, key = { "s-" + it.id }) { shortcut ->
                    ShortcutRow(shortcut, onDelete = {
                        shortcutStore.remove(shortcut.id)
                        shortcuts = shortcutStore.list()
                    })
                    HorizontalDivider(Modifier.padding(start = 24.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Tüm hafıza silinsin mi?") },
            text = { Text("Asistan senin hakkında öğrendiği her şeyi unutur. Bu geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clear()
                    entries = emptyList()
                    confirmClear = false
                }) { Text("Hepsini sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") } }
        )
    }
}

@Composable
private fun MemoryRow(entry: MemoryEntry, onDelete: () -> Unit) {
    val category = categoryOf(entry.text)
    val body = entry.text.removePrefix("[$category]").trim().takeIf { category != null } ?: entry.text
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (category != null) {
                Text(category, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(body, style = MaterialTheme.typography.bodyLarge)
            Text(
                SimpleDateFormat("d MMMM yyyy", Locale("tr", "TR")).format(Date(maxOf(entry.createdAt, entry.updatedAt))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Bu bilgiyi sil", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShortcutRow(shortcut: ShortcutStore.Shortcut, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(shortcut.goal, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${shortcut.steps.size} adım · ${shortcut.uses} kez kullanıldı",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Bu kısayolu sil", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun categoryOf(text: String): String? =
    Regex("^\\[([^\\]]{2,30})]").find(text.trim())?.groupValues?.get(1)
