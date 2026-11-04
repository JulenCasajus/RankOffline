package org.rankoffline.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-memory cache of decoded, downsampled local covers.
 *
 * This class has no knowledge of pack types, repositories, or databases.
 * [identity] must uniquely identify a cover owner across ALL packs: include the pack ID,
 * whether this is an item or a group, and the owner's ID.
 *
 * Call [invalidate] when replacing/removing a cover at the same path, or [invalidateAll]
 * after bulk catalog changes. Prefer immutable/versioned file paths for new covers.
 *
 * Returned Bitmaps are shared/borrowed: callers must never mutate or recycle them.
 */
class CoverBitmapCache {
    private data class CoverKey(val identity: String, val path: String)

    private val guard = Any()
    private var invalidationGeneration = 0L
    private val decodeLocks = Array(DECODE_LOCK_COUNT) { Mutex() }

    private val memoryCache = object : LruCache<CoverKey, Bitmap>(memoryBudgetKb()) {
        override fun sizeOf(key: CoverKey, value: Bitmap): Int =
            ((value.allocationByteCount.toLong() + 1023L) / 1024L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }

    /** Returns an already decoded bitmap without doing disk I/O; safe for Compose reads. */
    fun cachedBitmap(identity: String, path: String): Bitmap? = synchronized(guard) {
        val key = CoverKey(identity, path)
        val bitmap = memoryCache.get(key)
        if (bitmap?.isRecycled == true) {
            memoryCache.remove(key)
            null
        } else {
            bitmap
        }
    }

    /** Decodes at most once per key concurrently, off the main thread. */
    suspend fun loadBitmap(identity: String, path: String): Bitmap? = withContext(Dispatchers.IO) {
        cachedBitmap(identity, path)?.let { return@withContext it }

        val key = CoverKey(identity, path)
        val stripe = decodeLocks[(key.hashCode() and Int.MAX_VALUE) % decodeLocks.size]
        stripe.withLock {
            cachedBitmap(identity, path)?.let { return@withLock it }

            // A concurrent pack replacement can invalidate an in-flight read.
            // Retry once instead of reporting an erroneous missing cover to the UI.
            repeat(2) {
                val generationBeforeDecode = synchronized(guard) { invalidationGeneration }
                val bitmap = decodeSampled(path) ?: return@withLock null
                currentCoroutineContext().ensureActive()

                val inserted = synchronized(guard) {
                    if (generationBeforeDecode == invalidationGeneration) {
                        memoryCache.put(key, bitmap)
                        true
                    } else {
                        false
                    }
                }
                if (inserted) return@withLock bitmap
            }
            null
        }
    }

    /** Full-screen decode uses the same ORIGINAL file, not a second cached cover copy.
     * The viewer owns and recycles this bitmap; thumbnails stay in the bounded LruCache. */
    suspend fun loadViewerBitmap(path: String): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w !in 1..MAX_SOURCE_EDGE || h !in 1..MAX_SOURCE_EDGE ||
            w.toLong() * h > MAX_SOURCE_PIXELS) return@withContext null
        var sample = 1
        while (maxOf(w / sample, h / sample) > 3072 ||
            (w / sample).toLong() * (h / sample) > 6_000_000L) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    /** Drops one cached cover, including protection against an in-flight decode. */
    fun invalidate(identity: String, path: String) {
        synchronized(guard) {
            invalidationGeneration++
            memoryCache.remove(CoverKey(identity, path))
        }
    }

    /** Drops all cached covers; call this on pack replacement or mass cover deletion. */
    fun invalidateAll() {
        synchronized(guard) {
            invalidationGeneration++
            memoryCache.evictAll()
        }
    }

    private fun decodeSampled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 ||
            width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE ||
            width.toLong() * height.toLong() > MAX_SOURCE_PIXELS
        ) return null

        var sampleSize = 1
        while (
            maxOf(width / sampleSize, height / sampleSize) > MAX_DECODE_EDGE ||
            (width / sampleSize).toLong() * (height / sampleSize) > MAX_DECODE_PIXELS
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private companion object {
        const val DECODE_LOCK_COUNT = 32
        const val MAX_SOURCE_EDGE = 32_768
        const val MAX_SOURCE_PIXELS = 80_000_000L
        const val MAX_DECODE_EDGE = 1_600
        const val MAX_DECODE_PIXELS = 2_000_000L

        // A bounded fraction of the available heap, measured in KiB for LruCache.
        fun memoryBudgetKb(): Int =
            (Runtime.getRuntime().maxMemory() / 16L / 1024L)
                .coerceAtLeast(1024L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }
}
