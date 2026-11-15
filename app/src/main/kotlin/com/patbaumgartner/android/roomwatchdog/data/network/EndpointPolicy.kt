package com.patbaumgartner.android.roomwatchdog.data.network

import java.net.Inet6Address
import java.net.InetAddress
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
 * Audio rides the same base URL as the REST API and the WebSocket: the app only ever
 * talks to the scheme's default port (443 for https, 80 for http). The device's own
 * audio server on port 81 is a LAN detail - behind a reverse proxy, Traefik routes
 * /audio.pcm to the device's port 81 by path, so the announced audioPort is not
 * applied to the URL.
 */
internal fun deviceAudioUrl(value: String): HttpUrl =
    deviceBaseUrl(value).newBuilder().addPathSegment("audio.pcm").build()

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
    val literal = host.asIpLiteral()
    if (literal != null) return literal.isPrivateAddress()
    // A colon can only be an IPv6 literal, and one this cannot read is not one it should trust.
    return !host.contains(':') && host.isPrivateNetworkName()
}

/**
 * Only IP literals are resolved, so this never leaves the process. Prefix matching on the raw text
 * cannot do the job: `feature.example.com` shares its first three characters with `fe80::/10`.
 */
private fun String.asIpLiteral(): InetAddress? {
    val looksNumeric = contains(':') ||
            split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }
    if (!looksNumeric) return null
    return runCatching { InetAddress.getByName(this) }.getOrNull()
}

private fun InetAddress.isPrivateAddress(): Boolean =
    isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isUniqueLocalIpv6()

/** fc00::/7, the IPv6 counterpart of the RFC 1918 ranges; the JDK has no predicate for it. */
private fun InetAddress.isUniqueLocalIpv6(): Boolean =
    this is Inet6Address && (address[0].toInt() and 0xFE) == 0xFC

/** A name with no dot is a LAN hostname; the suffixes are the reserved local-network zones. */
private fun String.isPrivateNetworkName(): Boolean =
    !contains('.') || endsWith(".local") || endsWith(".lan") || endsWith(".home.arpa")
