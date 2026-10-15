package com.deepassist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepassist.data.ChatHistoryStore
import com.deepassist.data.ChatSummary
import com.deepassist.data.WhatsNew
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.deepassist.ui.EmptyState
import com.deepassist.ui.ReadinessCard
import com.deepassist.ui.ScreenHeader
import com.deepassist.util.Readiness
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    refreshTick: Int,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val chats = remember(refreshTick) {
        runCatching { ChatHistoryStore.get(context).list() }.getOrDefault(emptyList())
    }
    val problems = remember(refreshTick) { Readiness.problems(context) }
    val showWhatsNew = remember { mutableStateOf(WhatsNew.shouldShowCard(context)) }
    val groups = remember(chats) { chats.sortedByDescending { it.updatedAt }.groupBy { dayLabel(it.updatedAt) } }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader(title = "deepAssist", subtitle = "Sesli yardımcın") }
        item { ReadinessCard(problems = problems, onFix = onOpenSettings) }
        if (showWhatsNew.value) {
            item {
                WhatsNewCard(onDismiss = {
                    WhatsNew.dismissCard(context)
                    showWhatsNew.value = false
                })
            }
        }

        if (chats.isEmpty()) {
            item {
                EmptyState(
                    title = "İlk sohbetini başlat",
                    body = "Asistanla konuştukça sohbetler burada görünür. Bir sohbete dokunup kaldığın yerden devam edebilirsin."
                )
            }
        } else {
            groups.forEach { (day, dayChats) ->
                item(key = "h-$day") {
                    Text(
                        day,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 24.dp, top = 20.dp, bottom = 4.dp)
                            .semantics { heading() }
                    )
                }
                items(dayChats, key = { it.id }) { chat ->
                    ChatRow(chat = chat, onClick = { onOpenChat(chat.id) })
                    HorizontalDivider(
                        Modifier.padding(start = 24.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun WhatsNewCard(onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("2.0'da yenilikler", style = MaterialTheme.typography.headlineSmall)
            WhatsNew.items.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Tamam") }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                chat.title.ifBlank { "Sohbet" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${chat.messageCount} mesaj",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            SimpleDateFormat("HH:mm", Locale("tr", "TR")).format(Date(chat.updatedAt)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun dayLabel(timestamp: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun Calendar.sameDay(other: Calendar) =
        get(Calendar.YEAR) == other.get(Calendar.YEAR) && get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
    return when {
        day.sameDay(today) -> "Bugün"
        day.sameDay(yesterday) -> "Dün"
        else -> SimpleDateFormat("d MMMM EEEE", Locale("tr", "TR")).format(Date(timestamp))
    }
}
