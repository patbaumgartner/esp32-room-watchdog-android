package com.patbaumgartner.roomwatchdog.data.device

import java.io.IOException
import com.patbaumgartner.roomwatchdog.data.network.EndpointIssue
import com.patbaumgartner.roomwatchdog.data.network.EndpointValidationException
import com.patbaumgartner.roomwatchdog.data.network.deviceAudioUrl
import com.patbaumgartner.roomwatchdog.data.network.deviceBaseUrl
import com.patbaumgartner.roomwatchdog.data.network.deviceSocketUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class DeviceStatus(
    val presence: Boolean = false,
    val targetState: Int = 0,
    val movingDistanceCm: Int = 0,
    val movingEnergy: Int = 0,
    val stationaryDistanceCm: Int = 0,
    val stationaryEnergy: Int = 0,
    val micPeakToPeak: Int = 0,
    val micMin: Int = 0,
    val micMax: Int = 0,
    val audioStreaming: Boolean = false,
    val audioDroppedSamples: Long = 0,
    val telemetryClient: Boolean = false,
    val pushBackingOff: Boolean = false,
    val pushLost: Long = 0,
    val uptimeMs: Long = 0,
) {
    val moving: Boolean get() = targetState and 0x1 != 0

    /** Moving target wins, matching the firmware's own primaryDistanceCm(). */
    val primaryDistanceCm: Int
        get() = if (movingDistanceCm > 0) movingDistanceCm else stationaryDistanceCm
}

class DeviceException(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
    enum class Kind { InvalidUrl, Insecure, Unreachable, Auth, Busy, Unknown }
}

class DeviceClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun status(baseUrl: String, apiToken: String): Result<DeviceStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val base = deviceBaseUrl(baseUrl)
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegment("status").build())
                .header("Authorization", "Bearer $apiToken")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.code == 401 || response.code == 403) throw DeviceException(DeviceException.Kind.Auth)
                if (!response.isSuccessful) throw DeviceException(DeviceException.Kind.Unknown)
                json.decodeFromString<DeviceStatus>(body)
            }
        }.recoverCatching { error ->
            throw when (error) {
                is DeviceException -> error
                is EndpointValidationException -> DeviceException(
                    if (error.issue == EndpointIssue.Insecure) DeviceException.Kind.Insecure
                    else DeviceException.Kind.InvalidUrl,
                    error,
                )

                is IOException -> DeviceException(DeviceException.Kind.Unreachable, error)
                else -> DeviceException(DeviceException.Kind.Unknown, error)
            }
        }
    }

    /** Asks the firmware to re-baseline the radar. The device answers 202 and calibrates asynchronously. */
    suspend fun calibrate(baseUrl: String, apiToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = deviceBaseUrl(baseUrl)
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegment("calibrate").build())
                .header("Authorization", "Bearer $apiToken")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw DeviceException(DeviceException.Kind.Auth)
                if (response.code == 409) throw DeviceException(DeviceException.Kind.Busy)
                if (!response.isSuccessful) throw DeviceException(DeviceException.Kind.Unknown)
            }
        }.recoverCatching { error ->
            throw when (error) {
                is DeviceException -> error
                is EndpointValidationException -> DeviceException(
                    if (error.issue == EndpointIssue.Insecure) DeviceException.Kind.Insecure
                    else DeviceException.Kind.InvalidUrl,
                    error,
                )

                is IOException -> DeviceException(DeviceException.Kind.Unreachable, error)
                else -> DeviceException(DeviceException.Kind.Unknown, error)
            }
        }
    }

    fun audioRequest(baseUrl: String, apiToken: String, audioPort: Int? = DEFAULT_AUDIO_PORT): Request =
        Request.Builder()
            .url(deviceAudioUrl(baseUrl, audioPort))
            .header("Authorization", "Bearer $apiToken")
            .get()
            .build()

    /** Handshake for the live telemetry socket; okhttp upgrades the http(s) URL itself. */
    fun telemetryRequest(baseUrl: String, apiToken: String): Request = Request.Builder()
        .url(deviceSocketUrl(baseUrl))
        .header("Authorization", "Bearer $apiToken")
        .build()

    companion object {
        const val SAMPLE_RATE = 48_000

        /** The firmware's audio server; the socket's hello frame announces the live value. */
        const val DEFAULT_AUDIO_PORT = 81
    }
}
