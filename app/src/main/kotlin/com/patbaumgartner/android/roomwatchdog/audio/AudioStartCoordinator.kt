package com.patbaumgartner.android.roomwatchdog.audio

import com.patbaumgartner.android.roomwatchdog.data.device.DeviceClient
import com.patbaumgartner.android.roomwatchdog.data.network.deviceEndpoint

internal const val AUDIO_PORT_READINESS_TIMEOUT_MS = 3_000L

internal fun normalizeDeviceEndpoint(value: String): String? {
    if (value.isBlank()) return null
    return runCatching { deviceEndpoint(value).normalized() }
        .getOrElse { value.trim().trimEnd('/') }
        .takeIf { it.isNotEmpty() }
}

/** Holds the first-Hello port readiness for one endpoint, without Android or coroutine dependencies. */
internal class AudioStartCoordinator(
    initialEndpoint: String,
    private val onEndpointChanged: () -> Unit = {},
) {

    private var endpoint = normalizeDeviceEndpoint(initialEndpoint)
    private var generation = 1L
    private var cancellation = 0L
    private var audioPort = DeviceClient.DEFAULT_AUDIO_PORT
    private var helloHost: String? = null
    private var ready = false
    private var pending: PendingStart? = null

    @Synchronized
    fun currentGeneration(): Long = generation

    @Synchronized
    fun isCurrent(socketGeneration: Long): Boolean = socketGeneration == generation

    @Synchronized
    fun isCurrent(start: AudioStartDecision.Start): Boolean =
        start.generation == generation && start.cancellation == cancellation

    @Synchronized
    fun requestStart(alsoRecord: Boolean): AudioStartDecision {
        val currentEndpoint = endpoint ?: return AudioStartDecision.Ignore
        if (ready) return start(currentEndpoint, alsoRecord)

        val existing = pending
        val scheduleTimeout = existing == null
        pending = PendingStart(
            generation = generation,
            cancellation = cancellation,
            alsoRecord = alsoRecord || existing?.alsoRecord == true,
        )
        return AudioStartDecision.Wait(generation, cancellation, scheduleTimeout)
    }

    @Synchronized
    fun onHello(
        socketGeneration: Long,
        announcedPort: Int,
        announcedHost: String? = null,
    ): AudioStartDecision.Start? {
        if (socketGeneration != generation || announcedPort !in 1..65535) return null
        audioPort = announcedPort
        helloHost = announcedHost
        ready = true
        val currentEndpoint = endpoint ?: return null
        val requested = pending ?: return null
        pending = null
        return start(currentEndpoint, requested.alsoRecord)
    }

    @Synchronized
    fun onTimeout(wait: AudioStartDecision.Wait): AudioStartDecision.Start? {
        val requested = pending ?: return null
        if (ready || wait.generation != generation || requested.generation != generation ||
            wait.cancellation != requested.cancellation
        ) {
            return null
        }
        val currentEndpoint = endpoint ?: return null
        audioPort = DeviceClient.DEFAULT_AUDIO_PORT
        helloHost = null
        ready = true
        pending = null
        return start(currentEndpoint, requested.alsoRecord)
    }

    /** Invalidates both a pending request and a start already handed to the UI queue. */
    @Synchronized
    fun cancelPendingStart() {
        pending = null
        cancellation++
    }

    fun resetEndpoint(newEndpoint: String, force: Boolean = false): Boolean {
        val changed = synchronized(this) {
            val normalized = normalizeDeviceEndpoint(newEndpoint)
            if (!force && normalized == endpoint) {
                false
            } else {
                endpoint = normalized
                generation++
                cancellation++
                audioPort = DeviceClient.DEFAULT_AUDIO_PORT
                helloHost = null
                ready = false
                pending = null
                true
            }
        }
        if (changed) onEndpointChanged()
        return changed
    }

    private fun start(currentEndpoint: String, alsoRecord: Boolean) = AudioStartDecision.Start(
        endpoint = currentEndpoint,
        generation = generation,
        cancellation = cancellation,
        port = audioPort,
        helloHost = helloHost,
        alsoRecord = alsoRecord,
    )

    private data class PendingStart(
        val generation: Long,
        val cancellation: Long,
        val alsoRecord: Boolean,
    )
}

internal sealed interface AudioStartDecision {
    data class Start(
        val endpoint: String,
        val generation: Long,
        val cancellation: Long,
        val port: Int,
        val helloHost: String?,
        val alsoRecord: Boolean,
    ) : AudioStartDecision

    data class Wait(
        val generation: Long,
        val cancellation: Long,
        val scheduleTimeout: Boolean,
    ) : AudioStartDecision

    data object Ignore : AudioStartDecision
}
