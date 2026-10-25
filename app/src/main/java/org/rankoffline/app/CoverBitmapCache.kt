package org.rankoffline.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-local decoded image cache shared by every pack.
 *
 * Cover ownership, paths and download state remain in the common item/group repositories; this
 * class deliberately has no database or provider dependency.
 */
class CoverBitmapCache {
    private val memoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun cachedBitmap(identity: String, path: String): Bitmap? =
        synchronized(memoryCache) { memoryCache.get(cacheKey(identity, path)) }

    suspend fun loadBitmap(identity: String, path: String): Bitmap? = withContext(Dispatchers.IO) {
        cachedBitmap(identity, path)?.let { return@withContext it }
        val bitmap = BitmapFactory.decodeFile(path) ?: return@withContext null
        synchronized(memoryCache) { memoryCache.put(cacheKey(identity, path), bitmap) }
        bitmap
    }

    fun invalidate(identity: String, path: String) {
        synchronized(memoryCache) { memoryCache.remove(cacheKey(identity, path)) }
    }

    fun invalidateAll() {
        synchronized(memoryCache) { memoryCache.evictAll() }
    }

    private fun cacheKey(identity: String, path: String) = "$identity:$path"
}
