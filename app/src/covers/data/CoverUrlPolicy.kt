package org.rankoffline.app

import java.net.URI
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RFC 3986 percent-encoding for one untrusted template variable (path or query value). */
internal fun encodeUrlComponent(value: String?): String? {
    if (value.isNullOrBlank() || value.length > 2_048 || value.any(Char::isISOControl) ||
        '/' in value || '\\' in value
    ) return null
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    return buildString(bytes.size) {
        val digits = "0123456789ABCDEF"
        for (byte in bytes) {
            val b = byte.toInt() and 0xff
            if (b in 65..90 || b in 97..122 || b in 48..57 || b == 45 ||
                b == 46 || b == 95 || b == 126
            ) append(b.toChar())
            else {
                append('%')
                append(digits[b ushr 4])
                append(digits[b and 15])
            }
        }
    }
}

/**
 * URL-syntax and host allowlist check, not a guarantee against DNS rebinding.
 * The downloader must also enforce IP policy, redirects, response size and image type.
 */
internal fun isSafeHttpsUri(uri: URI, hosts: Set<String>?): Boolean {
    val value = uri.toASCIIString()
    if (value.length !in 1..4_096 || uri.rawFragment != null || uri.rawUserInfo != null ||
        !uri.scheme.equals("https", ignoreCase = true) || (uri.port != -1 && uri.port != 443)
    ) return false
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (!PUBLIC_IMAGE_HOST.matches(host) || host.endsWith(".local") ||
        host.endsWith(".localhost") || host.endsWith(".internal") ||
        host.endsWith(".test") || host.endsWith(".invalid") ||
        IPV4_LITERAL.matches(host) || ':' in host ||
        (hosts != null && host !in hosts)
    ) return false
    if (uri.normalize().rawPath != uri.rawPath) return false
    val path = uri.rawPath.orEmpty()
    val query = uri.rawQuery.orEmpty()
    val decodedPath = decodePercent(path) ?: return false
    val decodedQuery = decodePercent(query) ?: return false
    if ('\\' in decodedPath || decodedPath.any(Char::isISOControl) ||
        decodedQuery.any(Char::isISOControl) ||
        decodedPath.split('/').any { it == "." || it == ".." }
    ) return false
    // Prevent a server/proxy from interpreting a percent-encoded separator or nested traversal.
    if (ENCODED_PATH_SEPARATOR.containsMatchIn(path) ||
        ENCODED_TRAVERSAL.containsMatchIn(decodedPath) ||
        ENCODED_CONTROLS.containsMatchIn(decodedPath) ||
        ENCODED_CONTROLS.containsMatchIn(decodedQuery)
    ) return false
    return true
}

/** DNS defense used immediately before either metadata or image network I/O. */
internal fun resolvesToPublicAddresses(host: String): Boolean =
    InetAddress.getAllByName(host).takeIf { it.isNotEmpty() }?.all { address ->
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
            !address.isMulticastAddress && when (address) {
                is Inet4Address -> {
                    val bytes = address.address.map { it.toInt() and 255 }
                    val a = bytes[0]
                    val b = bytes[1]
                    a in 1..223 && !(a == 100 && b in 64..127) &&
                        !(a == 192 && b == 0) && !(a == 198 && b in 18..19) &&
                        !(a == 192 && b == 0 && bytes[2] == 2) &&
                        !(a == 198 && b == 51 && bytes[2] == 100) &&
                        !(a == 203 && b == 0 && bytes[2] == 113)
                }
                is Inet6Address -> {
                    val bytes = address.address.map { it.toInt() and 255 }
                    !(bytes[0] and 0xfe == 0xfc) &&
                        !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
                            bytes[2] == 0x0d && bytes[3] == 0xb8) &&
                        bytes[0] in 0x20..0x3f
                }
                else -> false
            }
    } == true

/** Parses either Retry-After seconds or its RFC 1123 date form, with a cooperative safety bound. */
internal fun retryAfterDelayMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val delay = raw.toLongOrNull()?.takeIf { it >= 0L }?.let { seconds ->
        if (seconds > MAX_RETRY_AFTER_MILLIS / 1_000L) MAX_RETRY_AFTER_MILLIS
        else seconds * 1_000L
    } ?: runCatching {
        ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli().minus(nowMillis)
    }.getOrNull()?.coerceAtLeast(0L)
    return delay?.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
}

private fun decodePercent(value: String): String? = runCatching {
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
}.getOrNull()

private val PUBLIC_IMAGE_HOST = Regex(
    "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"
)
private val IPV4_LITERAL = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
private val ENCODED_PATH_SEPARATOR = Regex("%(?:2[fF]|5[cC])")
private val ENCODED_TRAVERSAL = Regex("%(?:2[eE]|2[fF]|5[cC])")

private val ENCODED_CONTROLS = Regex("%(?:0[0-9a-fA-F]|1[0-9a-fA-F]|7[fF])")
private const val MAX_RETRY_AFTER_MILLIS = 60_000L
