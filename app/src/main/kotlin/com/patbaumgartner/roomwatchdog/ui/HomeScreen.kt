package com.patbaumgartner.roomwatchdog.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patbaumgartner.roomwatchdog.audio.StreamError
import com.patbaumgartner.roomwatchdog.audio.StreamPhase
import com.patbaumgartner.roomwatchdog.R
import com.patbaumgartner.roomwatchdog.ui.theme.WatchdogTheme
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
internal fun HomeScreen(state: HomeState, viewModel: AppViewModel) {
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
private fun streamErrorText(error: StreamError): String = when (error) {
    StreamError.Auth -> stringResource(R.string.error_stream_auth)
    StreamError.Busy -> stringResource(R.string.error_stream_busy)
    StreamError.Unreachable -> stringResource(R.string.error_stream_unreachable)
    StreamError.Unknown -> stringResource(R.string.error_stream_unknown)
}
