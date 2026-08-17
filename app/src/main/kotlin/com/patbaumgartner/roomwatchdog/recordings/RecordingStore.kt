package com.patbaumgartner.roomwatchdog.recordings

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Recording(
    val id: String,
    val fileName: String,
    val displayName: String,
    val startedAtMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
)

class RecordingStore(context: Context) {

    val directory = File(context.filesDir, "recordings")

    private val prefs = context.getSharedPreferences("watchdog_recordings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _recordings = MutableStateFlow(read())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    fun fileOf(recording: Recording) = File(directory, recording.fileName)

    fun add(file: File, durationMs: Long, sizeBytes: Long, startedAtMillis: Long): Recording {
        val recording = Recording(
            id = file.nameWithoutExtension,
            fileName = file.name,
            displayName = defaultName(startedAtMillis),
            startedAtMillis = startedAtMillis,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
        )
        write(listOf(recording) + _recordings.value)
        return recording
    }

    fun rename(id: String, displayName: String) {
        val name = displayName.trim()
        if (name.isEmpty()) return
        write(_recordings.value.map { if (it.id == id) it.copy(displayName = name) else it })
    }

    fun delete(id: String): Boolean {
        val recording = _recordings.value.firstOrNull { it.id == id } ?: return false
        val file = fileOf(recording)
        if (file.exists() && !file.delete()) return false
        write(_recordings.value.filterNot { it.id == id })
        return true
    }

    private fun defaultName(startedAtMillis: Long): String =
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(startedAtMillis))

    private fun read(): List<Recording> {
        val stored = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Recording>>(stored) }
            .getOrDefault(emptyList())
            .filter { File(directory, it.fileName).exists() }
    }

    private fun write(recordings: List<Recording>) {
        prefs.edit { putString(KEY, json.encodeToString(recordings)) }
        _recordings.value = recordings
    }

    private companion object {
        const val KEY = "recordings"
    }
}
