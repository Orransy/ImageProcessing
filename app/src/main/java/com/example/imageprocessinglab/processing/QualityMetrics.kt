package com.example.imageprocessinglab.processing

import android.graphics.Bitmap
import kotlin.math.log10

/** PSNR (RGB) và SSIM toàn cục trên kênh luma giữa ảnh tham chiếu và ảnh đã nén. */
object QualityMetrics {

    data class Score(val psnr: Double, val ssim: Double)

    fun compare(ref: Bitmap, test: Bitmap): Score {
        val w = ref.width
        val h = ref.height
        val scaled = test.width != w || test.height != h
        val t = if (scaled) Bitmap.createScaledBitmap(test, w, h, true) else test

        val a = IntArray(w * h)
        val b = IntArray(w * h)
        ref.getPixels(a, 0, w, 0, 0, w, h)
        t.getPixels(b, 0, w, 0, 0, w, h)
        if (scaled) t.recycle()

        var squaredError = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0

        for (i in a.indices) {
            val p = a[i]
            val q = b[i]

            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val qr = (q shr 16) and 0xFF
            val qg = (q shr 8) and 0xFF
            val qb = q and 0xFF

            val dr = pr - qr
            val dg = pg - qg
            val db = pb - qb
            squaredError += (dr * dr + dg * dg + db * db).toDouble()

            val x = 0.299 * pr + 0.587 * pg + 0.114 * pb
            val y = 0.299 * qr + 0.587 * qg + 0.114 * qb
            sumX += x
            sumY += y
            sumXX += x * x
            sumYY += y * y
            sumXY += x * y
        }

        val n = a.size.toDouble()
        val mse = squaredError / (n * 3.0)
        val psnr = if (mse <= 0.0) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)

        val mx = sumX / n
        val my = sumY / n
        val vx = sumXX / n - mx * mx
        val vy = sumYY / n - my * my
        val cov = sumXY / n - mx * my
        val c1 = (0.01 * 255.0) * (0.01 * 255.0)
        val c2 = (0.03 * 255.0) * (0.03 * 255.0)
        val ssim = ((2 * mx * my + c1) * (2 * cov + c2)) /
                ((mx * mx + my * my + c1) * (vx + vy + c2))

        return Score(psnr, ssim)
    }
}