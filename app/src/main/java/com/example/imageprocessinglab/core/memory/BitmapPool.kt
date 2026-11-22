package com.example.imageprocessinglab.core.memory

import android.graphics.Bitmap

class BitmapPool(private val config: MemoryPoolConfig) {
    private val bitmaps = LinkedHashMap<Bitmap, Boolean>()
    private var totalMemoryUsed = 0L
    private val lock = Any()

    fun acquire(width: Int, height: Int, bitmapConfig: Bitmap.Config): Bitmap = synchronized(lock) {
        require(width > 0) { "width must be greater than 0, got $width" }
        require(height > 0) { "height must be greater than 0, got $height" }

        val suitable = bitmaps.keys.find { bitmap ->
            !bitmap.isRecycled &&
                bitmap.width == width &&
                bitmap.height == height &&
                bitmap.config == bitmapConfig
        }

        if (suitable != null) {
            bitmaps.remove(suitable)
            totalMemoryUsed -= suitable.allocationByteCount.toLong()
            return suitable
        }

        return Bitmap.createBitmap(width, height, bitmapConfig)
    }

    fun release(bitmap: Bitmap) = synchronized(lock) {
        if (bitmap.isRecycled) {
            return@synchronized
        }

        if (bitmaps.containsKey(bitmap)) {
            return@synchronized
        }

        val estimatedSize = bitmap.allocationByteCount.toLong()
        if (estimatedSize > config.maxMemoryBytes) {
            bitmap.recycle()
            return@synchronized
        }

        bitmaps[bitmap] = true
        totalMemoryUsed += estimatedSize

        while (totalMemoryUsed > config.maxMemoryBytes || bitmaps.size > config.maxItemCount) {
            val oldestEntry = bitmaps.entries.firstOrNull() ?: break
            oldestEntry.key.recycle()
            bitmaps.remove(oldestEntry.key)
            totalMemoryUsed -= oldestEntry.key.allocationByteCount.toLong()
        }
    }

    fun clear() = synchronized(lock) {
        bitmaps.keys.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        bitmaps.clear()
        totalMemoryUsed = 0L
    }
}
