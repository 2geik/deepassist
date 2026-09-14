package com.deepassist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepassist.data.MemoryEntry
import com.deepassist.data.MemoryStore
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MemoryStore.get(context) }
    var entries by remember { mutableStateOf(store.list()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearConfirmCount by remember { mutableStateOf(0) }

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
            Text("Hafıza", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                clearConfirmCount++
                if (clearConfirmCount >= 2) {
                    store.clear()
                    entries = emptyList()
                    clearConfirmCount = 0
                    showClearConfirm = false
                } else {
                    showClearConfirm = true
                }
            }) { Text("Tümünü sil", color = Color(0xFFB00020), fontSize = 13.sp) }
        }

        if (showClearConfirm && clearConfirmCount < 2) {
            Surface(
                color = Color(0xFFFFF3E0),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Text(
                    "Emin misin? Tüm kayıtları silmek için tekrar tıkla.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp
                )
            }
        }

        HorizontalDivider()

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Kayıtlı bilgi yok.\nAsistan konuşmalardan öğrendikçe burada görünür.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries.reversed(), key = { it.id }) { entry ->
                    MemoryItem(
                        entry = entry,
                        onDelete = {
                            store.remove(entry.id)
                            entries = store.list()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryItem(entry: MemoryEntry, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.text, fontSize = 15.sp)
            Text(
                text = SimpleDateFormat("d MMM yyyy HH:mm", Locale("tr", "TR"))
                    .format(Date(entry.createdAt)),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onDelete) {
            Text("Sil", color = Color(0xFFB00020), fontSize = 13.sp)
        }
    }
}
