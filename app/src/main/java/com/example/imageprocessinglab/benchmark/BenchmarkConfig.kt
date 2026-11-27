package com.example.imageprocessinglab.benchmark

enum class ExecutionModel(val label: String) {
    SEQUENTIAL("Sequential"),
    THREAD_POOL("Thread Pool"),
    COROUTINES("Coroutines"),
    PIPELINE("Multi-stage Pipeline");

    companion object {
        fun fromLabel(label: String): ExecutionModel =
            values().firstOrNull { it.label == label } ?: SEQUENTIAL
    }
}

enum class DecodeStrategy(val label: String) {
    BASELINE("Baseline"),
    SAMPLE_SIZE("inSampleSize"),
    TARGET_SIZE("setTargetSize");

    companion object {
        fun fromLabel(label: String): DecodeStrategy =
            values().firstOrNull { it.label == label } ?: BASELINE
    }
}

/** Toàn bộ tham số của MỘT lần chạy (một batch ảnh). */
data class BenchmarkConfig(
    val label: String,
    val imageCount: Int,
    val threads: Int,
    val execution: ExecutionModel,
    val decode: DecodeStrategy,
    val engine: String,
    val format: String,
    val quality: Int,
    val bitmapPool: Boolean,
    val bufferPool: Boolean,
    /** Cạnh dài nhất của ảnh output (px). */
    val targetMaxSide: Int = 1024
)