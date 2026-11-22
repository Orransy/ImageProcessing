package com.example.imageprocessinglab.core.memory

class BufferPool(private val config: MemoryPoolConfig) {
    private val buffers = LinkedHashMap<ByteArray, Boolean>()
    private var totalMemoryUsed = 0L
    private val lock = Any()

    fun acquire(minSize: Int): ByteArray = synchronized(lock) {
        require(minSize > 0) { "minSize must be greater than 0, got $minSize" }

        val suitable = buffers.keys.find { it.size >= minSize }
        if (suitable != null) {
            buffers.remove(suitable)
            totalMemoryUsed -= suitable.size.toLong()
            return suitable
        }

        return ByteArray(minSize)
    }

    fun release(buffer: ByteArray) = synchronized(lock) {
        if (buffers.containsKey(buffer)) {
            return@synchronized
        }

        if (buffer.size > config.maxMemoryBytes) {
            return@synchronized
        }

        buffers[buffer] = true
        totalMemoryUsed += buffer.size.toLong()

        while (totalMemoryUsed > config.maxMemoryBytes || buffers.size > config.maxItemCount) {
            val oldestEntry = buffers.entries.firstOrNull() ?: break
            buffers.remove(oldestEntry.key)
            totalMemoryUsed -= oldestEntry.key.size.toLong()
        }
    }

    fun clear() = synchronized(lock) {
        buffers.clear()
        totalMemoryUsed = 0L
    }
}
