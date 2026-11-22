package com.example.imageprocessinglab.processing

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** ByteArrayOutputStream cho phép đọc thẳng mảng nội bộ, tránh copy bằng toByteArray(). */
class ReusableBuffer(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    val array: ByteArray get() = buf
    val length: Int get() = count
}

/**
 * Pool buffer byte (dùng cho dữ liệu ảnh nguồn và dữ liệu ảnh sau khi nén).
 * Khi tắt: mỗi lần acquire() cấp phát mới => tạo áp lực GC để so sánh.
 */
class BufferPool(
    val enabled: Boolean,
    private val maxIdle: Int = 32
) {
    private val idle = ConcurrentLinkedQueue<ReusableBuffer>()
    private val idleCount = AtomicInteger(0)

    fun acquire(): ReusableBuffer {
        if (enabled) {
            val buffer = idle.poll()
            if (buffer != null) {
                idleCount.decrementAndGet()
                buffer.reset()
                return buffer
            }
        }
        return ReusableBuffer(INITIAL_SIZE)
    }

    fun release(buffer: ReusableBuffer) {
        if (!enabled) return
        if (buffer.array.size > MAX_KEEP_SIZE) return
        if (idleCount.get() >= maxIdle) return
        idleCount.incrementAndGet()
        idle.offer(buffer)
    }

    private companion object {
        const val INITIAL_SIZE = 256 * 1024
        const val MAX_KEEP_SIZE = 32 * 1024 * 1024
    }
}

/**
 * Pool Bitmap đích (ảnh đã resize) theo từng kích thước (w, h).
 * Khi tắt: mỗi ảnh tạo Bitmap mới => để GC dọn.
 */
class BitmapPool(
    private val enabled: Boolean,
    private val maxIdle: Int = 24
) {
    private val idle = ConcurrentHashMap<Long, ConcurrentLinkedQueue<Bitmap>>()
    private val idleCount = AtomicInteger(0)

    private fun key(w: Int, h: Int): Long = (w.toLong() shl 32) or h.toLong()

    fun obtain(w: Int, h: Int): Bitmap {
        if (enabled) {
            val bitmap = idle[key(w, h)]?.poll()
            if (bitmap != null) {
                idleCount.decrementAndGet()
                return bitmap
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    fun release(bitmap: Bitmap) {
        if (!enabled) return
        if (bitmap.isRecycled || !bitmap.isMutable) return
        if (idleCount.get() >= maxIdle) return
        idle.getOrPut(key(bitmap.width, bitmap.height)) { ConcurrentLinkedQueue() }
            .offer(bitmap)
        idleCount.incrementAndGet()
    }

    fun clear() {
        idle.values.forEach { queue ->
            while (true) {
                val bitmap = queue.poll() ?: break
                bitmap.recycle()
            }
        }
        idle.clear()
        idleCount.set(0)
    }
}