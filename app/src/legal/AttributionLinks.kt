package org.rankoffline.app

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Invoke ONLY from an explicit user action after displaying the destination hostname.
 * Valid HTTPS syntax does not make a community-supplied website trustworthy.
 */
internal fun openAttributionLink(context: Context, url: String): Boolean {
    if (!AttributionUrlPolicy.isSafeExternalHttpsUrl(url)) return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
