package com.deepassist

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.deepassist.data.SecureStore
import com.deepassist.ui.ScreenHeader
import com.deepassist.ui.SectionCard
import com.deepassist.ui.theme.LocalExtraColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import com.deepassist.util.PermissionsHelper

@Composable
fun SettingsScreen(
    secureStore: SecureStore,
    refreshTick: Int,
    onRequestRuntimePermissions: () -> Unit,
    onRequestCallLogPermission: () -> Unit
) {
    val context = LocalContext.current
    var voiceEnabled by remember { mutableStateOf(secureStore.voiceResponseEnabled) }
    var framingGuide by remember { mutableStateOf(secureStore.framingGuideEnabled) }

    val hasRuntime = remember(refreshTick) { PermissionsHelper.hasAllRuntimePermissions(context) }
    val hasCallLog = remember(refreshTick) { PermissionsHelper.hasCallLog(context) }
    val hasOverlay = remember(refreshTick) { PermissionsHelper.hasOverlay(context) }
    val hasAccessibility = remember(refreshTick) { PermissionsHelper.isAccessibilityEnabled(context) }
    val accessibilityStalled = remember(refreshTick) {
        com.deepassist.service.AccessibilitySvc.isStalled(context)
    }
    val ignoresBattery = remember(refreshTick) { PermissionsHelper.isIgnoringBatteryOptimizations(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader(title = "Ayarlar", subtitle = "Görme engelliler için sesli asistan")

        SectionCard(title = "Ses") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sesli yanıt", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Cevaplar sesli olarak okunur",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
        }

        SectionCard(title = "Kamera") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Okurken yönlendir", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Belge okurken \"biraz sola çevir, yaklaştır\" diye tarif eder, yazı tam görününce okur",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = framingGuide,
                    onCheckedChange = {
                        framingGuide = it
                        secureStore.framingGuideEnabled = it
                    }
                )
            }
        }

        SectionCard(title = "İzinler") {
        PermissionRow(
            title = "Temel izinler",
            subtitle = "Mikrofon, rehber, arama, SMS, kamera, konum, bildirim",
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
            subtitle = if (accessibilityStalled) {
                "Servis durmuş: listede deepAssist'i kapatıp tekrar açın"
            } else {
                "Bildirim okuma ve güç tuşu tetikleme için"
            },
            granted = hasAccessibility && !accessibilityStalled,
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

        }

        // Xiaomi-specific extra permissions
        if (android.os.Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) SectionCard(title = "Xiaomi") {
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

        SectionCard(title = "Asistan") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    "Güç tuşuna iki kez bas, ana ekran tuşunu basılı tut ya da bildirimdeki " +
                        "deepAssist satırına dokun. Tınıyı duyunca konuşabilirsin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Varsayılan asistan yap") }
            }
        }

        SectionCard(title = "Hakkında") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Sürüm", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(24.dp))
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Verildi",
                tint = LocalExtraColors.current.success
            )
        } else {
            FilledTonalButton(onClick = onRequest) { Text("Aç") }
        }
    }
}
