package com.patbaumgartner.roomwatchdog.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.core.content.getSystemService
import com.patbaumgartner.roomwatchdog.data.device.DeviceClient
import com.patbaumgartner.roomwatchdog.recordings.M4aRecorder
import com.patbaumgartner.roomwatchdog.recordings.RecordingStore
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient

enum class StreamPhase { Idle, Connecting, Live }

enum class StreamError { Unreachable, Auth, Busy, Unknown }

data class StreamStatus(
    val phase: StreamPhase = StreamPhase.Idle,
    val recording: Boolean = false,
    val muted: Boolean = false,
    val listeningSinceMillis: Long? = null,
    val recordingSinceMillis: Long? = null,
    val error: StreamError? = null,
    val savedRecordingId: String? = null,
)

/**
 * Owns the device's single `/audio.pcm` connection and fans the same samples out to
 * playback, level metering and optional recording.
 */
class PcmStreamSession(
    private val context: Context,
    private val http: OkHttpClient,
    private val deviceClient: DeviceClient,
    private val recordingStore: RecordingStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService<AudioManager>()

    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var job: Job? = null
    private var call: Call? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var activeTrack: AudioTrack? = null

    private val recorder = M4aRecorder(recordingStore.directory)

    @Volatile
    private var recordingRequested = false

    val isActive: Boolean get() = _status.value.phase != StreamPhase.Idle

    fun start(baseUrl: String, apiToken: String, alsoRecord: Boolean = false) {
        if (isActive) {
            if (alsoRecord) startRecording()
            return
        }
        recordingRequested = alsoRecord
        _status.value = StreamStatus(phase = StreamPhase.Connecting)
        job = scope.launch { stream(baseUrl, apiToken) }
    }

    fun stop() {
        // Recording and playback share this session. Stopping it must always finalise the
        // recording before the stream is torn down.
        stopRecording()
        call?.cancel()
        job?.cancel()
    }

    fun startRecording() {
        if (_status.value.phase == StreamPhase.Live) recordingRequested = true
    }

    fun stopRecording() {
        recordingRequested = false
    }

    fun toggleMuted() {
        if (_status.value.phase != StreamPhase.Live) return
        val muted = !_status.value.muted
        activeTrack?.setVolume(if (muted) 0f else 1f)
        _status.value = _status.value.copy(muted = muted)
    }

    fun consumeSavedRecording() {
        _status.value = _status.value.copy(savedRecordingId = null)
    }

    fun clearError() {
        if (_status.value.phase == StreamPhase.Idle) _status.value = StreamStatus()
    }

    private suspend fun stream(baseUrl: String, apiToken: String) {
        var track: AudioTrack? = null
        var error: StreamError? = null
        var savedId: String? = null
        var recordingStartedAt = 0L

        try {
            val response = http.newCall(deviceClient.audioRequest(baseUrl, apiToken))
                .also { call = it }
                .execute()

            response.use {
                when {
                    it.code == 401 || it.code == 403 -> throw StreamFailure(StreamError.Auth)
                    it.code == 409 -> throw StreamFailure(StreamError.Busy)
                    !it.isSuccessful -> throw StreamFailure(StreamError.Unknown)
                }
                val source = it.body.byteStream()

                track = buildTrack().apply { play() }
                activeTrack = track
                requestFocus()
                _status.value = _status.value.copy(
                    phase = StreamPhase.Live,
                    listeningSinceMillis = System.currentTimeMillis(),
                )

                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read <= 0) break

                    track.write(buffer, 0, read)
                    _level.value = levelOf(buffer, read)

                    if (recordingRequested && !recorder.isRecording) {
                        recorder.start()
                        recordingStartedAt = System.currentTimeMillis()
                        _status.value = _status.value.copy(
                            recording = true,
                            recordingSinceMillis = recordingStartedAt,
                        )
                    }
                    if (recorder.isRecording) {
                        if (recordingRequested) {
                            recorder.feed(buffer, read)
                        } else {
                            savedId = finishRecording(recordingStartedAt)
                            _status.value = _status.value.copy(
                                recording = false,
                                recordingSinceMillis = null,
                                savedRecordingId = savedId,
                            )
                        }
                    }
                }
            }
        } catch (failure: StreamFailure) {
            error = failure.error
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Deliberate stop; fall through to cleanup.
        } catch (_: Exception) {
            error = if (_status.value.phase == StreamPhase.Connecting) {
                StreamError.Unreachable
            } else {
                StreamError.Unknown
            }
        } finally {
            if (recorder.isRecording) {
                savedId = finishRecording(recordingStartedAt) ?: savedId
            }
            track?.runCatching { stop() }
            track?.runCatching { release() }
            activeTrack = null
            abandonFocus()
            call = null
            recordingRequested = false
            _level.value = 0f
            _status.value = StreamStatus(error = error, savedRecordingId = savedId)
        }
    }

    private fun finishRecording(startedAt: Long): String? {
        val result = recorder.stop() ?: return null
        return recordingStore.add(
            file = result.file,
            durationMs = result.durationMs,
            sizeBytes = result.sizeBytes,
            startedAtMillis = if (startedAt > 0) startedAt else System.currentTimeMillis(),
        ).id
    }

    private fun buildTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            DeviceClient.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(DeviceClient.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, BUFFER_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun audioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes())
            .build()
        focusRequest = request
        audioManager?.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun levelOf(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            count++
            index += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count) / Short.MAX_VALUE
        return min(1f, (rms * LEVEL_GAIN).toFloat())
    }

    private class StreamFailure(val error: StreamError) : Exception()

    private companion object {
        const val BUFFER_BYTES = 4096
        const val LEVEL_GAIN = 4.0
    }
}
