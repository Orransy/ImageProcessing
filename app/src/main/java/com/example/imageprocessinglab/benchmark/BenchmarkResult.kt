package com.example.imageprocessinglab.benchmark

data class BenchmarkResult(
    val experiment: String,
    val totalTimeMs: Long,
    val throughput: Double,
    val peakMemoryMb: Double,
    val gcCount: Int,
    val medianLatencyMs: Double,
    val p95LatencyMs: Double,
    val outputSizeKb: Double,
    val psnr: Double,
    val ssim: Double,
    val speedup: Double,
    // --- bổ sung (có giá trị mặc định nên code cũ vẫn dùng được) ---
    val label: String = "",
    val threadsUsed: Int = 1,
    val processed: Int = 0,
    val failed: Int = 0
)