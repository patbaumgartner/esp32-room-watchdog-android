package com.patbaumgartner.roomwatchdog.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patbaumgartner.roomwatchdog.AppVisibility
import com.patbaumgartner.roomwatchdog.ui.theme.RoomWatchdogTheme

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel.applyIntent(intent)
        setContent {
            RoomWatchdogTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                NotificationPermissionEffect(state.config.isConfigured)
                LocalNetworkPermissionEffect(
                    configured = state.config.isConfigured,
                    onGranted = viewModel::refreshStatus,
                    onDenied = viewModel::localNetworkPermissionDenied,
                )
                RoomWatchdogApp(state, viewModel)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.applyIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onEnterForeground()
        // The live screen says everything the alerts would, so clear whatever is already queued.
        (application as com.patbaumgartner.roomwatchdog.RoomWatchdogApp).container.notifier.cancelEventAlerts()
    }

    override fun onStop() {
        AppVisibility.onLeaveForeground()
        super.onStop()
    }
}

@Composable
private fun NotificationPermissionEffect(configured: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(configured) {
        if (configured &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun LocalNetworkPermissionEffect(
    configured: Boolean,
    onGranted: () -> Unit,
    onDenied: () -> Unit,
) {
    if (Build.VERSION.SDK_INT < 37 || !configured) return
    val context = LocalContext.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    val currentOnDenied by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) currentOnGranted() else currentOnDenied()
    }
    LaunchedEffect(configured) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}

@Composable
private fun RoomWatchdogApp(state: HomeState, viewModel: AppViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Crossfade(targetState = state.screen, label = "screen") { screen ->
            when (screen) {
                AppScreen.Home -> HomeScreen(state, viewModel)
                AppScreen.Recordings -> RecordingsScreen(viewModel)
                AppScreen.Settings -> SettingsScreen(state, viewModel)
            }
        }
    }
}
