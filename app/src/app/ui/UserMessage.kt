package org.rankoffline.app

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/** Messages owned by the APK. */
sealed interface UserMessage {
    data class Text(
        @StringRes val resource: Int,
        val arguments: List<Any> = emptyList()
    ) : UserMessage

    data class Plural(
        @PluralsRes val resource: Int,
        val quantity: Int,
        val arguments: List<Any> = emptyList()
    ) : UserMessage

    fun resolve(context: Context): String = when (this) {
        is Text -> context.getString(resource, *arguments.toTypedArray())
        is Plural -> context.resources.getQuantityString(resource, quantity, *arguments.toTypedArray())
    }
}
