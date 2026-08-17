package com.patbaumgartner.roomwatchdog.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.patbaumgartner.roomwatchdog.AppVisibility
import com.patbaumgartner.roomwatchdog.audio.StreamError
import com.patbaumgartner.roomwatchdog.audio.StreamPhase
import com.patbaumgartner.roomwatchdog.R
import com.patbaumgartner.roomwatchdog.recordings.Recording
import com.patbaumgartner.roomwatchdog.ui.theme.RoomWatchdogTheme
import com.patbaumgartner.roomwatchdog.ui.theme.WatchdogTheme
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

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

@Composable
private fun SetupForm(setup: SetupState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val localNetworkPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.saveSetup() else viewModel.localNetworkPermissionDenied()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        SetupField(stringResource(R.string.settings_room), setup.roomName) {
            viewModel.updateSetup { copy(roomName = it) }
        }
        SetupField(stringResource(R.string.settings_device_url), setup.deviceUrl) {
            viewModel.updateSetup { copy(deviceUrl = it) }
        }
        SetupField(stringResource(R.string.settings_api_token), setup.apiToken, secret = true) {
            viewModel.updateSetup { copy(apiToken = it) }
        }
        SetupField(stringResource(R.string.settings_gotify_url), setup.gotifyUrl) {
            viewModel.updateSetup { copy(gotifyUrl = it) }
        }
        SetupField(
            stringResource(R.string.settings_gotify_token),
            setup.clientToken,
            secret = true
        ) { viewModel.updateSetup { copy(clientToken = it) } }
        if (setup.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(setup.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 37 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    viewModel.saveSetup()
                }
            },
            enabled = !setup.busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (setup.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.settings_save))
        }
        if (setup.deviceUrl.isBlank() || setup.apiToken.isBlank()) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = viewModel::useDeveloperSetup,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text(stringResource(R.string.settings_use_developer)) }
        }
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(R.string.settings_credentials_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WatchdogTheme.accents.textMuted,
        )
    }
}

@Composable
private fun SetupField(label: String, value: String, secret: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun HomeScreen(state: HomeState, viewModel: AppViewModel) {
    val stream = state.stream
    val status = state.deviceStatus
    val isLive = stream.phase == StreamPhase.Live
    val isConnecting = stream.phase == StreamPhase.Connecting
    val recording = stream.recording
    val presence = state.presenceDetected
    val sound = state.soundDetected
    var menuExpanded by remember { mutableStateOf(false) }

    val headline = when {
        isConnecting -> stringResource(R.string.state_connecting)
        recording -> stringResource(R.string.state_recording)
        isLive -> stringResource(R.string.state_listening)
        presence -> stringResource(R.string.state_presence)
        sound -> stringResource(R.string.state_sound)
        else -> stringResource(R.string.state_quiet)
    }
    val detail = when {
        state.message != null -> state.message
        stream.error != null -> streamErrorText(stream.error)
        !state.config.isConfigured -> stringResource(R.string.state_not_connected)
        isLive -> stringResource(R.string.state_audio_playing)
        presence -> distanceText(status?.primaryDistanceCm ?: 0)
        sound -> stringResource(R.string.state_sound_activity)
        !state.connected -> stringResource(R.string.state_not_connected)
        else -> stringResource(R.string.state_room_calm)
    }
    val accent = if (recording) WatchdogTheme.accents.recording else MaterialTheme.colorScheme.primary
    // A disconnected device still shows the wave, only faded, so the screen never looks broken.
    val waveColor = if (isLive || state.connected) accent else accent.copy(alpha = 0.3f)
    val headlineColor by animateColorAsState(
        targetValue = when {
            recording -> WatchdogTheme.accents.recording
            !isLive && presence -> WatchdogTheme.accents.presence
            !isLive && sound -> WatchdogTheme.accents.warning
            else -> MaterialTheme.colorScheme.onBackground
        },
        label = "headlineColor",
    )

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    state.config.roomName.ifBlank { stringResource(R.string.default_room) },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.menu_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_calibrate)) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        onClick = { menuExpanded = false; viewModel.calibrate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_recordings)) },
                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                        onClick = { menuExpanded = false; viewModel.openRecordings() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_settings)) },
                        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        onClick = { menuExpanded = false; viewModel.openSettings() },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        AnimatedContent(targetState = headline, label = "headline") { text ->
            Text(
                text,
                style = MaterialTheme.typography.displayLarge,
                color = headlineColor,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyLarge,
            color = WatchdogTheme.accents.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        RoomSignalIndicators(presence = presence, sound = sound)

        Spacer(Modifier.height(28.dp))
        AudioWave(
            level = state.waveLevel,
            color = waveColor,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).height(88.dp),
        )
        Spacer(Modifier.height(56.dp))

        ListenControl(
            live = isLive,
            connecting = isConnecting,
            level = state.waveLevel,
            accent = accent,
            contentColor = if (recording) Color.White else MaterialTheme.colorScheme.onPrimary,
            onStart = { if (state.config.isConfigured) viewModel.startListening() else viewModel.openSettings() },
        )
        Spacer(Modifier.height(20.dp))
        Text(
            when {
                isLive && stream.noiseLearning -> stringResource(R.string.state_learning_room)
                isLive -> ""
                isConnecting -> stringResource(R.string.state_opening_stream)
                state.config.isConfigured -> stringResource(R.string.state_tap_listen)
                else -> stringResource(R.string.state_tap_setup)
            },
            style = MaterialTheme.typography.labelLarge,
            color = WatchdogTheme.accents.brand,
        )

        Spacer(Modifier.weight(1f))

        // Reserved so starting or stopping a session never nudges the rest of the screen.
        Box(
            modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLive) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleAction(
                        onClick = viewModel::toggleMuted,
                        icon = if (stream.muted) {
                            Icons.AutoMirrored.Outlined.VolumeOff
                        } else {
                            Icons.AutoMirrored.Outlined.VolumeUp
                        },
                        contentDescription = stringResource(
                            if (stream.muted) R.string.action_unmute else R.string.action_mute,
                        ),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CircleAction(
                        onClick = viewModel::toggleNoiseFilter,
                        icon = if (stream.noiseFiltered) Icons.Outlined.FilterAlt else Icons.Outlined.FilterAltOff,
                        contentDescription = stringResource(
                            if (stream.noiseFiltered) {
                                R.string.action_noise_filter_off
                            } else {
                                R.string.action_noise_filter_on
                            },
                        ),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CircleAction(
                        onClick = if (recording) viewModel::stopRecording else viewModel::startRecording,
                        icon = if (recording) Icons.Outlined.Stop else Icons.Outlined.FiberManualRecord,
                        contentDescription = stringResource(
                            if (recording) R.string.cd_stop_recording else R.string.cd_record,
                        ),
                        containerColor = WatchdogTheme.accents.recording,
                        contentColor = Color.White,
                    )
                    CircleAction(
                        onClick = viewModel::stopListening,
                        icon = Icons.Outlined.Stop,
                        contentDescription = stringResource(R.string.cd_stop),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RoomSignalIndicators(presence: Boolean, sound: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (presence) {
                SignalIndicator(
                    label = stringResource(R.string.signal_presence),
                    icon = Icons.Outlined.PersonOutline,
                    color = WatchdogTheme.accents.presence,
                )
            }
            if (sound) {
                SignalIndicator(
                    label = stringResource(R.string.signal_noise),
                    icon = Icons.Outlined.GraphicEq,
                    color = WatchdogTheme.accents.warning,
                )
            }
        }
    }
}

@Composable
private fun SignalIndicator(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CircleAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(54.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun distanceText(distanceCm: Int): String = if (distanceCm <= 0) {
    stringResource(R.string.state_movement)
} else {
    stringResource(R.string.state_distance, distanceCm / 100f)
}

private const val WAVE_BARS = 44
private const val WAVE_FRAME_MS = 70L

/**
 * A rolling waveform that never stops moving: it breathes gently in a silent room and swells with
 * whatever the device or the live stream is hearing.
 */
@Composable
private fun AudioWave(level: Float, color: Color, modifier: Modifier = Modifier) {
    val currentLevel by rememberUpdatedState(level)
    val bars = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(0f) } } }
    LaunchedEffect(Unit) {
        var phase = 0f
        while (true) {
            phase += 0.35f
            val breathing = 0.07f + 0.025f * sin(phase.toDouble()).toFloat()
            val jitter = 0.65f + Random.nextFloat() * 0.35f
            bars.removeAt(0)
            bars.add((currentLevel.coerceIn(0f, 1f) * jitter + breathing).coerceIn(0f, 1f))
            delay(WAVE_FRAME_MS)
        }
    }
    Canvas(modifier) {
        val slot = size.width / WAVE_BARS
        val barWidth = slot * 0.4f
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
        bars.forEachIndexed { index, amplitude ->
            // Taper both ends so the wave fades into the page instead of stopping abruptly.
            val taper = (min(index, WAVE_BARS - 1 - index) / (WAVE_BARS * 0.3f)).coerceIn(0f, 1f)
            val height = (size.height * amplitude * taper).coerceAtLeast(barWidth)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = corner,
            )
        }
    }
}

@Composable
private fun ListenControl(
    live: Boolean,
    connecting: Boolean,
    level: Float,
    accent: Color,
    contentColor: Color,
    onStart: () -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "connect").animateFloat(
        initialValue = 0.94f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val halo by animateFloatAsState(
        targetValue = if (live) level.coerceIn(0f, 1f) else 0f,
        label = "halo",
    )
    Box(modifier = Modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size((136 + halo * 34).dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
        )
        Box(
            modifier = Modifier
                .size((136 * if (connecting) pulse else 1f).dp)
                .clip(CircleShape)
                .background(accent)
                .clickable(enabled = !live && !connecting, onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = contentColor,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    Icons.Outlined.Headphones,
                    contentDescription = stringResource(
                        if (live) R.string.cd_listening else R.string.cd_listen,
                    ),
                    modifier = Modifier.size(44.dp),
                    tint = contentColor,
                )
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RecordingsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.recording_share_chooser)
    val app =
        (context.applicationContext as com.patbaumgartner.roomwatchdog.RoomWatchdogApp)
    val recordings by app.container.recordings.recordings.collectAsStateWithLifecycle()
    var recordingToRename by remember { mutableStateOf<Recording?>(null) }
    var recordingToDelete by remember { mutableStateOf<Recording?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteFailed by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        PageHeader(stringResource(R.string.recordings_title), viewModel::goHome)
        if (recordings.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.recordings_empty), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.recordings_empty_hint), color = WatchdogTheme.accents.textMuted)
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                recordings.forEach { recording ->
                    RecordingRow(
                        recording = recording,
                        onPlay = {
                            if (!openRecording(context, app.container.recordings.fileOf(recording))) {
                                actionError = R.string.recording_open_failed
                            }
                        },
                        onShare = {
                            if (!shareRecording(
                                    context,
                                    app.container.recordings.fileOf(recording),
                                    recording.displayName,
                                    shareChooserTitle,
                                )
                            ) {
                                actionError = R.string.recording_share_failed
                            }
                        },
                        onRename = {
                            renameValue = recording.displayName
                            recordingToRename = recording
                        },
                        onDelete = {
                            deleteFailed = false
                            recordingToDelete = recording
                        },
                    )
                }
            }
        }
    }

    recordingToRename?.let { recording ->
        AlertDialog(
            onDismissRequest = { recordingToRename = null },
            title = { Text(stringResource(R.string.recording_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text(stringResource(R.string.recording_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameValue.isNotBlank(),
                    onClick = {
                        viewModel.renameRecording(recording.id, renameValue)
                        recordingToRename = null
                    },
                ) { Text(stringResource(R.string.action_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { recordingToRename = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    recordingToDelete?.let { recording ->
        AlertDialog(
            onDismissRequest = { recordingToDelete = null },
            title = { Text(stringResource(R.string.recording_delete_title)) },
            text = {
                Text(
                    if (deleteFailed) {
                        stringResource(R.string.recording_delete_failed)
                    } else {
                        stringResource(R.string.recording_delete_message, recording.displayName)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.deleteRecording(recording.id)) {
                            recordingToDelete = null
                        } else {
                            deleteFailed = true
                        }
                    },
                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { recordingToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    actionError?.let { message ->
        AlertDialog(
            onDismissRequest = { actionError = null },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = { actionError = null }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(recording.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.recording_details, recording.durationMs / 1000, recording.sizeBytes / 1024),
                color = WatchdogTheme.accents.textMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.recording_actions))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_play)) },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = { menuExpanded = false; onPlay() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share)) },
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    onClick = { menuExpanded = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

private fun openRecording(context: Context, file: java.io.File): Boolean = runCatching {
    check(file.isFile)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, RECORDING_MIME_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
}.isSuccess

private fun shareRecording(context: Context, file: java.io.File, title: String, chooserTitle: String): Boolean =
    runCatching {
        check(file.isFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(RECORDING_MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }.isSuccess

@Composable
private fun SettingsScreen(state: HomeState, viewModel: AppViewModel) {
    val resources = LocalResources.current
    var licensesShown by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        PageHeader(stringResource(R.string.settings_title), viewModel::goHome)
        SetupForm(state.setup, viewModel)
        TextButton(
            onClick = { licensesShown = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.settings_open_source_licenses))
        }
        Spacer(Modifier.height(32.dp))
    }

    if (licensesShown) {
        val licenseText = remember(resources) {
            resources.openRawResource(R.raw.manrope_license)
                .bufferedReader()
                .use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { licensesShown = false },
            title = { Text(stringResource(R.string.settings_open_source_licenses_title)) },
            text = {
                Text(
                    text = licenseText,
                    modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { licensesShown = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun streamErrorText(error: StreamError): String = when (error) {
    StreamError.Auth -> stringResource(R.string.error_stream_auth)
    StreamError.Busy -> stringResource(R.string.error_stream_busy)
    StreamError.Unreachable -> stringResource(R.string.error_stream_unreachable)
    StreamError.Unknown -> stringResource(R.string.error_stream_unknown)
}

private const val RECORDING_MIME_TYPE = "audio/mp4"
