package org.rankoffline.app

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
