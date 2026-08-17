package com.patbaumgartner.roomwatchdog.recordings

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Encodes the device's raw PCM into AAC-LC/M4A. Writes to a temporary file and only
 * publishes it once a valid end-of-stream has been muxed.
 */
class M4aRecorder(private val outputDir: File) {

    data class Result(val file: File, val durationMs: Long, val sizeBytes: Long)

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamples = 0L
    private var tempFile: File? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    val isRecording: Boolean get() = codec != null

    fun start() {
        if (isRecording) return
        check(outputDir.isDirectory || outputDir.mkdirs()) { "Cannot create the recordings directory" }
        val temp = File(outputDir, "recording-${System.currentTimeMillis()}-${UUID.randomUUID()}.tmp")
        tempFile = temp

        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }

        try {
            codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            totalSamples = 0
            trackIndex = -1
            muxerStarted = false
        } catch (error: Exception) {
            abort()
            throw error
        }
    }

    fun feed(pcm: ByteArray, length: Int) {
        val encoder = codec ?: return
        var offset = 0
        var deadline = System.nanoTime() + FEED_TIMEOUT_NS
        while (offset < length) {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                drainAvailable()
                check(System.nanoTime() < deadline) { "Audio encoder stopped accepting input" }
                continue
            }
            val buffer = checkNotNull(encoder.getInputBuffer(inputIndex)) { "Audio encoder input unavailable" }
            buffer.clear()
            val chunk = minOf(buffer.capacity(), length - offset)
            buffer.put(pcm, offset, chunk)
            encoder.queueInputBuffer(inputIndex, 0, chunk, presentationTimeUs(), 0)
            totalSamples += chunk / BYTES_PER_SAMPLE
            offset += chunk
            deadline = System.nanoTime() + FEED_TIMEOUT_NS
            drainAvailable()
        }
    }

    fun stop(): Result? {
        val encoder = codec ?: return null
        val temp = tempFile

        val completed = runCatching { queueEndOfStream(encoder) && drainToEnd() }.getOrDefault(false)

        val durationMs = totalSamples * 1000 / SAMPLE_RATE
        val published = runCatching {
            release()
            if (!completed || temp == null || !muxerWroteData || !temp.exists() || temp.length() == 0L) {
                temp?.delete()
                null
            } else {
                val target = File(outputDir, temp.name.removeSuffix(".tmp") + ".m4a")
                if (temp.renameTo(target)) {
                    target
                } else {
                    temp.delete()
                    null
                }
            }
        }.getOrNull()

        reset()
        return published?.let { Result(it, durationMs, it.length()) }
    }

    fun abort() {
        runCatching { release() }
        tempFile?.delete()
        reset()
    }

    private var muxerWroteData = false

    private fun presentationTimeUs(): Long = totalSamples * 1_000_000L / SAMPLE_RATE

    private fun queueEndOfStream(encoder: MediaCodec): Boolean {
        val deadline = System.nanoTime() + STOP_TIMEOUT_NS
        while (System.nanoTime() < deadline) {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                return true
            }
            drainAvailable()
        }
        return false
    }

    private fun drainAvailable() {
        drain(waitForEnd = false)
    }

    private fun drainToEnd(): Boolean = drain(waitForEnd = true)

    private fun drain(waitForEnd: Boolean): Boolean {
        val encoder = codec ?: return false
        val activeMuxer = muxer ?: return false
        val deadline = System.nanoTime() + STOP_TIMEOUT_NS
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, if (waitForEnd) TIMEOUT_US else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEnd || System.nanoTime() >= deadline) return false
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = activeMuxer.addTrack(encoder.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    }
                }

                outputIndex >= 0 -> {
                    val buffer: ByteBuffer? = encoder.getOutputBuffer(outputIndex)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && bufferInfo.size > 0 && muxerStarted && !isConfig) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        activeMuxer.writeSampleData(trackIndex, buffer, bufferInfo)
                        muxerWroteData = true
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun release() {
        codec?.runCatching { stop() }
        codec?.runCatching { release() }
        if (muxerStarted) {
            muxer?.runCatching { stop() }
        }
        muxer?.runCatching { release() }
    }

    private fun reset() {
        codec = null
        muxer = null
        tempFile = null
        trackIndex = -1
        muxerStarted = false
        muxerWroteData = false
        totalSamples = 0
    }

    private companion object {
        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val BIT_RATE = 96_000
        const val MAX_INPUT_SIZE = 16_384
        const val BYTES_PER_SAMPLE = 2
        const val TIMEOUT_US = 10_000L
        const val FEED_TIMEOUT_NS = 2_000_000_000L
        const val STOP_TIMEOUT_NS = 2_000_000_000L
    }
}
