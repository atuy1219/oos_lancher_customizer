package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import android.util.LruCache

/** The loader is lazy: a hit performs neither resource loading nor rendering. */
internal class IconBitmapCache(maxBytes: Int = 16 * 1024 * 1024) {
    private val bitmaps = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val failures = object : LruCache<String, Boolean>(256) {}
    private var generation = 0L

    fun getOrLoad(key: String, loader: () -> Bitmap?): Bitmap? {
        repeat(2) {
            val epoch = synchronized(this) {
                bitmaps.get(key)?.let { return it }
                if (failures.get(key) == true) return null
                generation
            }
            val result = loader()
            synchronized(this) {
                // Retry instead of returning an obsolete theme result to a view.
                if (epoch == generation) {
                    if (result == null) failures.put(key, true) else bitmaps.put(key, result)
                    return result
                }
            }
        }
        return null
    }

    @Synchronized
    fun clear() {
        generation++
        bitmaps.evictAll()
        failures.evictAll()
    }
}
