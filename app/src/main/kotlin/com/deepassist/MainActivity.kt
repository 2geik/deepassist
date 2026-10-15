package com.deepassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.deepassist.ui.theme.DeepAssistTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.deepassist.data.SecureStore
import com.deepassist.service.AssistantForegroundService
import com.deepassist.util.PermissionsHelper

class MainActivity : ComponentActivity() {

    // Simple state-based navigation — no nav library needed
    sealed interface Screen {
        data object Home : Screen
        data object Settings : Screen
        data object Memory : Screen
        data class Detail(val chatId: String) : Screen
    }

    private lateinit var secureStore: SecureStore

    private val currentScreen = mutableStateOf<Screen>(Screen.Home)

    // Bumped on resume / permission results so screens re-read state
    private val refreshTick = mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshTick.intValue++
            AssistantForegroundService.start(this)
        }

    private val callLogPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshTick.intValue++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureStore = SecureStore(this)
        AssistantForegroundService.start(this)
        if (!PermissionsHelper.hasAllRuntimePermissions(this)) {
            permissionLauncher.launch(PermissionsHelper.runtimePermissions)
        }
        enableEdgeToEdge()
        setContent {
            DeepAssistTheme {
                // System back goes to Home from any other screen
                BackHandler(enabled = currentScreen.value != Screen.Home) {
                    currentScreen.value = Screen.Home
                    refreshTick.intValue++
                }
                AppScaffold()
            }
        }
    }

    @Composable
    private fun AppScaffold() {
        val screen = currentScreen.value
        val goTo: (Screen) -> Unit = {
            currentScreen.value = it
            refreshTick.intValue++
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                // A chat opened from the list is a full screen of its own
                if (screen !is Screen.Detail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        NavItem("Sohbetler", Icons.Outlined.Home, screen == Screen.Home) { goTo(Screen.Home) }
                        NavItem("Hafıza", Icons.Outlined.Person, screen == Screen.Memory) { goTo(Screen.Memory) }
                        NavItem("Ayarlar", Icons.Outlined.Settings, screen == Screen.Settings) { goTo(Screen.Settings) }
                    }
                }
            }
        ) { padding ->
            AnimatedContent(
                targetState = screen,
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "screen"
            ) { target ->
                when (target) {
                    is Screen.Home -> HomeScreen(
                        refreshTick = refreshTick.intValue,
                        onOpenChat = { goTo(Screen.Detail(it)) },
                        onOpenSettings = { goTo(Screen.Settings) }
                    )
                    is Screen.Settings -> SettingsScreen(
                        secureStore = secureStore,
                        refreshTick = refreshTick.intValue,
                        onRequestRuntimePermissions = {
                            permissionLauncher.launch(PermissionsHelper.runtimePermissions)
                        },
                        onRequestCallLogPermission = {
                            callLogPermissionLauncher.launch(android.Manifest.permission.READ_CALL_LOG)
                        }
                    )
                    is Screen.Memory -> MemoryScreen()
                    is Screen.Detail -> ChatDetailScreen(
                        chatId = target.chatId,
                        onBack = { goTo(Screen.Home) },
                        onDeleted = { goTo(Screen.Home) }
                    )
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.NavItem(
        label: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        NavigationBarItem(
            selected = selected,
            onClick = onClick,
            icon = { Icon(icon, contentDescription = null) },
            label = { Text(label) }
        )
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
        AssistantForegroundService.start(this)
    }
}
