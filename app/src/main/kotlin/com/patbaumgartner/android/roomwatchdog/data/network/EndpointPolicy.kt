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

internal data class DeviceEndpoint(val url: HttpUrl, val explicitPort: Boolean) {
    val isIpLiteral: Boolean get() = url.host.asIpLiteral() != null

    fun normalized(): String {
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val port = if (explicitPort) ":${url.port}" else ""
        return "${url.scheme}://$host$port${url.encodedPath}"
    }
}

internal fun deviceEndpoint(value: String): DeviceEndpoint =
    DeviceEndpoint(deviceBaseUrl(value), value.hasExplicitPort())

/**
 * HTTPS and every explicitly configured HTTP port (including :80) stay on the configured proxy.
 * A no-port HTTP URL is direct by default for migration compatibility: a valid announced/default
 * audio port is applied immediately for IP literals, and for hostnames unless a non-blank Hello
 * host identifies a different device. The explicit :80 override is deterministic for proxies whose
 * Hello is absent or whose host is indistinguishable from the device.
 */
internal fun deviceAudioUrl(value: String, audioPort: Int?, helloHost: String? = null): HttpUrl {
    val endpoint = deviceEndpoint(value)
    val base = endpoint.url
    val builder = base.newBuilder().addPathSegment("audio.pcm")
    val directDevice = endpoint.isIpLiteral || !base.host.isDifferentHelloDevice(helloHost)
    if (base.scheme == "http" && !endpoint.explicitPort && directDevice &&
        audioPort != null && audioPort in 1..65535
    ) {
        builder.port(audioPort)
    }
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

private fun String.hasExplicitPort(): Boolean {
    val authority = trim().substringAfter("://").substringBefore('/')
    if (authority.startsWith("[")) return authority.substringAfter("]", "").startsWith(":")
    return authority.substringAfterLast('@').contains(':')
}

private fun String.isDifferentHelloDevice(helloHost: String?): Boolean {
    val announced = helloHost?.trim()?.trimEnd('.')?.lowercase().orEmpty()
    if (announced.isEmpty()) return false
    val configured = trimEnd('.').lowercase()
    if (configured == announced) return false

    val configuredLabel = configured.substringBefore('.')
    val announcedLabel = announced.substringBefore('.')
    val localAlias = configuredLabel == announcedLabel &&
        (configured == configuredLabel || announced == announcedLabel) &&
        announced.isPrivateNetworkName()
    return !localAlias
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
