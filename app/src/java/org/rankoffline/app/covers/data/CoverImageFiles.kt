package org.rankoffline.app

import android.graphics.BitmapFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Validates images using decoder bounds; no full-resolution bitmap is allocated. */
internal object CoverImageFiles {
    const val MAX_BYTES = 15L * 1024 * 1024
    private const val MAX_DIMENSION = 20_000
    private const val MAX_PIXELS = 100_000_000L

    fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth in 1..MAX_DIMENSION && options.outHeight in 1..MAX_DIMENSION &&
                options.outWidth.toLong() * options.outHeight.toLong() in 1..MAX_PIXELS
        }.getOrDefault(false)
    }

    fun promote(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
