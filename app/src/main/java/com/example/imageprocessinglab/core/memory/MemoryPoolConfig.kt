package com.example.imageprocessinglab.core.memory

data class MemoryPoolConfig(
    val maxMemoryBytes: Long = 32L * 1024 * 1024,
    val maxItemCount: Int = 10
) {
    init {
        require(maxMemoryBytes > 0) { "maxMemoryBytes must be greater than 0, got $maxMemoryBytes" }
        require(maxItemCount > 0) { "maxItemCount must be greater than 0, got $maxItemCount" }
    }
}
