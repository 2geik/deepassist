package com.deepassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
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
        setContent {
            MaterialTheme {
                // System back goes to Home from any sub-screen
                BackHandler(enabled = currentScreen.value != Screen.Home) {
                    currentScreen.value = Screen.Home
                    refreshTick.intValue++
                }

                when (val screen = currentScreen.value) {
                    is Screen.Home -> HomeScreen(
                        refreshTick = refreshTick.intValue,
                        onOpenChat = { currentScreen.value = Screen.Detail(it) },
                        onOpenSettings = { currentScreen.value = Screen.Settings },
                        onOpenMemory = { currentScreen.value = Screen.Memory }
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
                    is Screen.Memory -> MemoryScreen(
                        onBack = {
                            currentScreen.value = Screen.Home
                            refreshTick.intValue++
                        }
                    )
                    is Screen.Detail -> ChatDetailScreen(
                        chatId = screen.chatId,
                        onBack = {
                            currentScreen.value = Screen.Home
                            refreshTick.intValue++
                        },
                        onDeleted = {
                            currentScreen.value = Screen.Home
                            refreshTick.intValue++
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
        AssistantForegroundService.start(this)
    }
}
