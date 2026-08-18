package com.patbaumgartner.roomwatchdog.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patbaumgartner.roomwatchdog.R
import com.patbaumgartner.roomwatchdog.RoomWatchdogApp
import com.patbaumgartner.roomwatchdog.recordings.Recording
import com.patbaumgartner.roomwatchdog.ui.theme.WatchdogTheme
import java.io.File

@Composable
internal fun RecordingsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.recording_share_chooser)
    val app = context.applicationContext as RoomWatchdogApp
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
                Icon(WatchdogIcons.MoreHoriz, contentDescription = stringResource(R.string.recording_actions))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_play)) },
                    leadingIcon = { Icon(WatchdogIcons.PlayArrow, contentDescription = null) },
                    onClick = { menuExpanded = false; onPlay() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share)) },
                    leadingIcon = { Icon(WatchdogIcons.Share, contentDescription = null) },
                    onClick = { menuExpanded = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    leadingIcon = { Icon(WatchdogIcons.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    leadingIcon = { Icon(WatchdogIcons.DeleteOutline, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}


private fun openRecording(context: Context, file: File): Boolean = runCatching {
    check(file.isFile)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, RECORDING_MIME_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
}.isSuccess

private fun shareRecording(context: Context, file: File, title: String, chooserTitle: String): Boolean =
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

private const val RECORDING_MIME_TYPE = "audio/mp4"
