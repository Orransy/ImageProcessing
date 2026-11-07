package com.example.imageprocessinglab.benchmark

import android.os.Handler
import android.os.Looper
import kotlin.math.max

class BenchmarkRunner {

    private val handler = Handler(Looper.getMainLooper())

    fun runMock(
        experiment: String,
        imageCount: Int,
        threads: Int,
        bitmapPool: Boolean,
        bufferPool: Boolean,
        format: String,
        callback: (BenchmarkResult) -> Unit
    ) {

        val safeThreads = threads.coerceIn(1, 8)

        // Thời gian giả lập trên mỗi ảnh
        var timePerImage = 20.0

        if (experiment == "E3" || experiment == "E4") {
            timePerImage /= safeThreads.toDouble()
        }

        if (bitmapPool) {
            timePerImage *= 0.9
        }

        if (bufferPool) {
            timePerImage *= 0.95
        }

        if (format == "WebP") {
            timePerImage *= 1.15
        }

        if (format == "AVIF") {
            timePerImage *= 1.4
        }

        val totalTime = max(
            1L,
            (imageCount * timePerImage).toLong()
        )

        val throughput =
            imageCount * 1000.0 / totalTime

        val memory = if (bitmapPool) {
            70.0 + safeThreads * 4.0
        } else {
            90.0 + imageCount * 0.08
        }

        val outputSizePerImage = when (format) {
            "JPEG" -> 350.0
            "WebP" -> 230.0
            "AVIF" -> 190.0
            else -> 350.0
        }

        val result = BenchmarkResult(
            experiment = experiment,
            totalTimeMs = totalTime,
            throughput = throughput,
            peakMemoryMb = memory,
            gcCount = if (bitmapPool) 2 else 8,
            medianLatencyMs = timePerImage * 0.9,
            p95LatencyMs = timePerImage * 1.5,
            outputSizeKb = imageCount * outputSizePerImage,
            psnr = 35.0,
            ssim = 0.95,
            speedup = 20.0 / timePerImage
        )

        // Giả lập thời gian chạy
        handler.postDelayed({
            callback(result)
        }, 1200)
    }
}
