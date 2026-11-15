package com.patbaumgartner.android.roomwatchdog.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.roomwatchdog.BuildConfig
import com.patbaumgartner.android.roomwatchdog.R
import com.patbaumgartner.android.roomwatchdog.ui.theme.WatchdogTheme

@Composable
internal fun AuthorNote(onShowTypefaceLicense: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val authorUrl = stringResource(R.string.about_author_url)
    val authorName = stringResource(R.string.about_author_name)
    val authorNote = stringResource(R.string.about_author)
    val authorStart = authorNote.indexOf(authorName)
    val styledAuthorNote = buildAnnotatedString {
        append(authorNote)
        if (authorStart >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), authorStart, authorStart + authorName.length)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(
            text = stringResource(R.string.about_heading),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onShowTypefaceLicense, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.settings_open_source_licenses), color = WatchdogTheme.accents.brand)
        }
        Text(
            text = stringResource(R.string.about_app_license),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { uriHandler.openUri(authorUrl) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(styledAuthorNote, style = MaterialTheme.typography.bodyMedium, color = WatchdogTheme.accents.brand)
        }
        Text(
            text = stringResource(R.string.about_crafted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
