package com.example.imageprocessinglab.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.imageprocessinglab.NativeImageProcessor
import java.io.File
import java.io.FileOutputStream

object NativeSimdBenchmark {

    data class E5Result(
        val skiaMsPerImage: Double,
        val cppPlainMsPerImage: Double,
        val neonMsPerImage: Double,
        val outputDir: String
    )

    /**
     * Thực nghiệm E5: Cố định 1 luồng, đối chiếu tốc độ (ms/ảnh) giữa Skia, C++ thuần và ARM NEON,
     * đồng thời lưu 3 ảnh PNG đầu ra phục vụ đánh giá chất lượng PSNR / SSIM.
     */
    fun runExperimentE5(
        context: Context,
        sampleBitmap: Bitmap,
        targetWidth: Int = 800,
        targetHeight: Int = 600,
        iterations: Int = 30
    ): E5Result {
        val dstSkia = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val dstPlain = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val dstNeon = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        // Warm-up 5 lượt để CPU ổn định xung nhịp
        repeat(5) {
            NativeImageProcessor.resizeInto(sampleBitmap, dstSkia, NativeImageProcessor.ENGINE_SKIA)
            NativeImageProcessor.resizeInto(sampleBitmap, dstPlain, NativeImageProcessor.ENGINE_CPP_PLAIN)
            NativeImageProcessor.resizeInto(sampleBitmap, dstNeon, NativeImageProcessor.ENGINE_NEON)
        }

        val t0 = SystemClock.elapsedRealtimeNanos()
        repeat(iterations) {
            NativeImageProcessor.resizeInto(sampleBitmap, dstSkia, NativeImageProcessor.ENGINE_SKIA)
        }
        val skiaMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0 / iterations

        val t1 = SystemClock.elapsedRealtimeNanos()
        repeat(iterations) {
            NativeImageProcessor.resizeInto(sampleBitmap, dstPlain, NativeImageProcessor.ENGINE_CPP_PLAIN)
        }
        val plainMs = (SystemClock.elapsedRealtimeNanos() - t1) / 1_000_000.0 / iterations

        val t2 = SystemClock.elapsedRealtimeNanos()
        repeat(iterations) {
            NativeImageProcessor.resizeInto(sampleBitmap, dstNeon, NativeImageProcessor.ENGINE_NEON)
        }
        val neonMs = (SystemClock.elapsedRealtimeNanos() - t2) / 1_000_000.0 / iterations

        val outDir = File(context.getExternalFilesDir(null), "e5_outputs").apply { mkdirs() }
        savePng(dstSkia, File(outDir, "out_skia.png"))
        savePng(dstPlain, File(outDir, "out_cpp_plain.png"))
        savePng(dstNeon, File(outDir, "out_neon.png"))

        return E5Result(skiaMs, plainMs, neonMs, outDir.absolutePath)
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}