package com.deepassist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepassist.data.ChatHistoryStore
import com.deepassist.data.ChatMessage
import com.deepassist.service.AssistantForegroundService
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatDetailScreen(chatId: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ChatHistoryStore.get(context) }
    var record by remember { mutableStateOf(store.load(chatId)) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Geri") }
            Spacer(Modifier.weight(1f))
            Text(
                text = record?.title?.take(30) ?: "Sohbet",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
        }

        HorizontalDivider()

        // Messages
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            record?.messages?.forEach { msg ->
                MessageBubble(msg)
                Spacer(Modifier.height(6.dp))
            }
        }

        // Bottom bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { AssistantForegroundService.resumeChat(context, chatId) },
                modifier = Modifier.weight(1f)
            ) { Text("Sohbete devam et") }
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB00020))
            ) { Text("Sil") }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Sohbeti sil") },
            text = { Text("Bu sohbet kalıcı olarak silinecek.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(chatId)
                    showDeleteConfirm = false
                    onDeleted()
                }) { Text("Sil", color = Color(0xFFB00020)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Vazgeç") }
            }
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val bgColor = if (isUser) Color(0xFF6750A4) else Color(0xFFF1F0F5)
    val textColor = if (isUser) Color.White else Color(0xFF212121)
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bgColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(text = msg.text, color = textColor, fontSize = 15.sp)
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.ts)),
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
