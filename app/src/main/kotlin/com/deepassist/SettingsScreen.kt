package com.deepassist

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deepassist.data.SecureStore
import com.deepassist.service.AssistantForegroundService
import com.deepassist.util.PermissionsHelper

@Composable
fun SettingsScreen(
    secureStore: SecureStore,
    refreshTick: Int,
    onRequestRuntimePermissions: () -> Unit,
    onRequestCallLogPermission: () -> Unit
) {
    val context = LocalContext.current
    var openAiKey by remember { mutableStateOf(secureStore.openAiApiKey) }
    var deepseekKey by remember { mutableStateOf(secureStore.deepseekApiKey) }
    var tavilyKey by remember { mutableStateOf(secureStore.tavilyApiKey) }
    var voiceEnabled by remember { mutableStateOf(secureStore.voiceResponseEnabled) }

    val hasRuntime = remember(refreshTick) { PermissionsHelper.hasAllRuntimePermissions(context) }
    val hasCallLog = remember(refreshTick) { PermissionsHelper.hasCallLog(context) }
    val hasOverlay = remember(refreshTick) { PermissionsHelper.hasOverlay(context) }
    val hasAccessibility = remember(refreshTick) { PermissionsHelper.isAccessibilityEnabled(context) }
    val ignoresBattery = remember(refreshTick) { PermissionsHelper.isIgnoringBatteryOptimizations(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("deepAssist", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Görme engelliler için sesli asistan",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = openAiKey,
            onValueChange = { openAiKey = it },
            label = { Text("OpenAI API Anahtarı (STT için)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = deepseekKey,
            onValueChange = { deepseekKey = it },
            label = { Text("DeepSeek API Anahtarı (LLM için)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = tavilyKey,
            onValueChange = { tavilyKey = it },
            label = { Text("Tavily API Anahtarı (internet araması, isteğe bağlı)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                secureStore.openAiApiKey = openAiKey
                secureStore.deepseekApiKey = deepseekKey
                secureStore.tavilyApiKey = tavilyKey
                AssistantForegroundService.start(context)
                Toast.makeText(context, "Kaydedildi", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Kaydet") }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sesli yanıt", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Cevaplar sesli olarak okunur",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Switch(
                checked = voiceEnabled,
                onCheckedChange = {
                    voiceEnabled = it
                    secureStore.voiceResponseEnabled = it
                }
            )
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("İzinler", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        PermissionRow(
            title = "Temel izinler",
            subtitle = "Mikrofon, rehber, arama, SMS, bildirim",
            granted = hasRuntime,
            onRequest = onRequestRuntimePermissions
        )
        PermissionRow(
            title = "Arama kayıtları (çağrı geçmişi)",
            subtitle = "Gelen, giden, cevapsız aramaları okumak için",
            granted = hasCallLog,
            onRequest = onRequestCallLogPermission
        )
        PermissionRow(
            title = "Ekran üstü gösterim",
            subtitle = "Cevap kutusunun görünmesi için",
            granted = hasOverlay,
            onRequest = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
        )
        PermissionRow(
            title = "Erişilebilirlik servisi",
            subtitle = "Bildirim okuma ve güç tuşu tetikleme için",
            granted = hasAccessibility,
            onRequest = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        )
        PermissionRow(
            title = "Bildirim erişimi",
            subtitle = "WhatsApp mesajlarını okuma ve bildirimden cevap verme için",
            granted = remember(refreshTick) { PermissionsHelper.isNotificationListenerEnabled(context) },
            onRequest = {
                val component = android.content.ComponentName(
                    context,
                    com.deepassist.service.MessageListenerService::class.java
                ).flattenToString()
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT < 30) error("no detail screen")
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
                    )
                }.onFailure {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                }
            }
        )
        PermissionRow(
            title = "Pil optimizasyonu kapalı",
            subtitle = "Arka planda sürekli çalışabilmek için",
            granted = ignoresBattery,
            onRequest = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }.onFailure {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        )

        // Full-screen intent permission (Android 14+)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val canUseFsi = remember(refreshTick) { PermissionsHelper.canUseFullScreenIntent(context) }
            PermissionRow(
                title = "Tam ekran bildirim",
                subtitle = "Kilit ekranında asistanın açılabilmesi için",
                granted = canUseFsi,
                onRequest = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            )
        }

        // Xiaomi-specific extra permissions
        if (android.os.Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) {
            PermissionRow(
                title = "Xiaomi ek izinleri",
                subtitle = "Kilit ekranında göster + Arka planda açılır pencere",
                granted = false, // no reliable granted-check for MIUI perms
                onRequest = {
                    runCatching {
                        context.startActivity(
                            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                                setClassName(
                                    "com.miui.securitycenter",
                                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                                )
                                putExtra("extra_pkgname", context.packageName)
                            }
                        )
                    }.onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Varsayılan Asistan Yap") }

        Spacer(Modifier.height(12.dp))
        Text(
            "Kullanım: bildirimdeki deepAssist satırına dokun, ekranı kapatıp hemen aç " +
                "veya ana ekran tuşunu basılı tut. Titreşimden sonra konuşabilirsin.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Text("Verildi", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
        } else {
            Button(onClick = onRequest) { Text("İzin Ver") }
        }
    }
}
