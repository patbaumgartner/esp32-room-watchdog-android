package com.patbaumgartner.roomwatchdog.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class EndpointIssue { Invalid, Insecure }

internal class EndpointValidationException(val issue: EndpointIssue) : IllegalArgumentException(issue.name)

internal fun deviceBaseUrl(value: String): HttpUrl {
    val url = parseBaseUrl(value)
    if (url.scheme == "https") return url
    if (url.scheme == "http" && url.host.isPrivateNetworkHost()) return url
    throw EndpointValidationException(EndpointIssue.Insecure)
}

/**
 * Audio comes from a second, synchronous server - an async chunked response could not keep up with
 * 48 kHz - so the stream keeps the API's host and scheme but rides on its own port. A port already
 * in the configured URL is left alone: that is how a reverse proxy in front of the device is
 * addressed.
 */
internal fun deviceAudioUrl(value: String, audioPort: Int?): HttpUrl {
    val base = deviceBaseUrl(value)
    val builder = base.newBuilder().addPathSegment("audio.pcm")
    if (audioPort != null && base.port == HttpUrl.defaultPort(base.scheme)) builder.port(audioPort)
    return builder.build()
}

internal fun deviceSocketUrl(value: String): HttpUrl =
    deviceBaseUrl(value).newBuilder().addPathSegment("ws").build()

internal fun gotifyBaseUrl(value: String): HttpUrl {
    val url = parseBaseUrl(value)
    if (url.scheme != "https") throw EndpointValidationException(EndpointIssue.Insecure)
    return url
}

private fun parseBaseUrl(value: String): HttpUrl {
    val url = value.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw EndpointValidationException(EndpointIssue.Invalid)
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
        throw EndpointValidationException(EndpointIssue.Invalid)
    }
    return url
}

private fun String.isPrivateNetworkHost(): Boolean {
    val host = lowercase()
    if (host == "localhost" || host == "::1" || !host.contains('.')) return true
    if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
    if (host.startsWith("fc") || host.startsWith("fd") ||
        host.startsWith("fe8") || host.startsWith("fe9") ||
        host.startsWith("fea") || host.startsWith("feb")
    ) {
        return true
    }

    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
}
