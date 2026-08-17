package com.patbaumgartner.roomwatchdog.ui

import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.patbaumgartner.roomwatchdog.R
import com.patbaumgartner.roomwatchdog.ui.theme.WatchdogTheme

@Composable
internal fun SettingsScreen(state: HomeState, viewModel: AppViewModel) {
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
