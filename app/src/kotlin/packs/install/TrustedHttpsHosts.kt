package org.rankoffline.app

import java.net.URI

/** Exact HTTPS host allow-list shared by registry and archive downloads. */
internal class TrustedHttpsHosts(allowedHosts: Set<String>) {
    private val hosts = allowedHosts.map { it.trim().lowercase() }.toSet().also { values ->
        require(values.isNotEmpty())
        require(values.none { it.isBlank() || '/' in it || ':' in it })
    }

    fun approves(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in hosts &&
            uri.userInfo == null && uri.fragment == null &&
            uri.port in listOf(-1, 443)
}
